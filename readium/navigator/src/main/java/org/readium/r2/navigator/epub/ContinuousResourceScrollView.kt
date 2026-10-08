/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package org.readium.r2.navigator.epub

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.math.max
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.R2BasicWebView
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter.PageResource.EpubReflowable
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.mediatype.MediaType
import timber.log.Timber

/** One native scroll coordinate positions separate Readium resource pages. */
internal class ContinuousResourceScrollView(
    context: Context,
    private val fragments: FragmentManager,
    resources: List<EpubReflowable>,
    private val expectsNavigation: Boolean,
    private val onPositionChanged: () -> Unit,
    private val onFirstPositionShown: () -> Unit = {},
    private val onNavigationEnded: (locator: Locator, landed: Boolean) -> Unit = { _, _ -> },
) : ScrollView(context) {

    private data class Slot(
        val resource: EpubReflowable,
        val frame: FrameLayout,
        var extent: Int = 0,
        var measured: Boolean = false,
        var ready: Boolean = false,
        var page: R2EpubPageFragment? = null,
        var measurementGeneration: Int = 0,
        var cssViewportWidth: Double = 0.0,
        var commandedLocal: Int = 0,
        var rejectChildScroll: Boolean = false,
        var hiddenTopCss: Int = 0,
        var stagedLocal: Int? = null,
        var fresh: Boolean = true,
        var freshLocal: Int = -1,
        var adjustPending: Boolean = false,
    )

    private var writingChildScroll = false
    private var revealed = false

    /** Set by [dispose]. Work that was posted before it must not mount a page or move the offset. */
    private var disposed = false
    private var navigationRequested = false
    private var geometryDroppedDuringCss = false
    private val flingScroller = android.widget.OverScroller(context)
    private var flingBias = 0

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val slots = resources.map { resource ->
        Slot(
            resource,
            FrameLayout(context).apply {
                id = View.generateViewId()
                clipChildren = true
            }
        )
    }
    private var updatePosted = false
    private var geometryEpoch = 0
    private var pendingNavigation: PendingNavigation? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchStartY = 0f
    private var touchMoving = false
    private var touchActive = false
    private var parentOwnsGesture = false
    private var coastingGesture = false
    private var settleGeneration = 0
    private var skipWebViewFocusRectangle = false
    private var anchorCaptureGeneration = 0
    private var cachedAnchor: ViewportAnchor? = null
    private var reflowAnchor: ViewportAnchor? = null
    private var reflowGeneration = 0
    private var cssTransitionGeneration = 0
    private var cssCapturePending = false
    private val queuedCssScripts = mutableListOf<String>()
    private var cssTransition: CssTransition? = null
    private var cssGateObserver: ViewTreeObserver? = null
    private var cssGateListener: ViewTreeObserver.OnPreDrawListener? = null
    private var cssObserverIgnoreUntil = 0L

    private data class CssMeasurement(
        val extent: Int,
        val viewportWidth: Double,
        val anchorY: Double?,
    )

    private data class CssTransition(
        val generation: Int,
        val anchor: ViewportAnchor,
        val measurements: MutableMap<Int, CssMeasurement> = mutableMapOf(),
        var pass: Int = 0,
        var commitReady: Boolean = false,
        var committed: Boolean = false,
    )

    private fun scheduleIdle() {
        val generation = ++settleGeneration
        postDelayed({
            if (generation == settleGeneration && !touchActive) {
                coastingGesture = false
                updateVisibleRegions()
            }
        }, IDLE_DELAY_MS)
    }

    private fun beginReaderGesture() {
        if (parentOwnsGesture) return
        parentOwnsGesture = true
        reflowAnchor = null
        reflowGeneration++
        pendingNavigation?.let { endNavigation(it, NavigationEnd.SUPERSEDED) }
        cachedAnchor = null
        anchorCaptureGeneration++
    }

    private data class ViewportAnchor(
        val index: Int,
        val locator: Locator,
        val deltaCss: Double,
        val sampleScreenY: Int,
    )

    private data class PendingNavigation(
        val index: Int,
        val locator: Locator,
        var resolving: Boolean = false,
        var localY: Double? = null,
        var anchored: Boolean = false,
        var queryGeneration: Int = 0,
        var preparedIndices: Set<Int> = emptySet(),
        var landingPosted: Boolean = false,
        val anchorDeltaCss: Double = 0.0,
        val alignmentY: Int? = null,
        val kind: NavigationKind = NavigationKind.JUMP,
        var stagedEpoch: Int = -1,
        var stagedY: Int = -1,
        var stageRemaining: Int = 0,
        var ended: Boolean = false,
    )

    init {
        isVerticalScrollBarEnabled = true
        addView(column, LayoutParams(-1, -2))
        slots.forEach { column.addView(it.frame, LinearLayout.LayoutParams(-1, 1)) }
        column.visibility = View.INVISIBLE
        postDelayed({ failInitialLoad(pendingNavigation?.index, LoadFailure.TIMEOUT) }, LOAD_TIMEOUT_MS)
        post { updateWindow() }
    }

    /** The surface stays hidden until the first position is on screen. */
    private fun reveal() {
        if (revealed) return
        revealed = true
        column.visibility = View.VISIBLE
        updateVisibleRegions()
        notifyOwner { onFirstPositionShown() }
    }

    /**
     * Calls the owner after the current pass. The owner may navigate from a callback, and a
     * callback in the middle of a pass would let the rest of the pass overwrite that request.
     */
    private fun notifyOwner(callback: () -> Unit) {
        post { if (!disposed) callback() }
    }

    /**
     * Ends [request] and reports it. A request that stays pending stops the surface from
     * keeping the reading position, so every path that gives up on one comes through here.
     */
    private fun endNavigation(request: PendingNavigation, end: NavigationEnd) {
        if (pendingNavigation === request) {
            pendingNavigation = null
            // A position that was staged for this request must not outlive it.
            if (end != NavigationEnd.LANDED) slots.forEach { it.stagedLocal = null }
        }
        if (request.ended) return
        request.ended = true
        if (end.fault) Timber.w("navigation ended $end href=${request.locator.href}")
        if (request.kind == NavigationKind.JUMP) {
            notifyOwner { onNavigationEnded(request.locator, end == NavigationEnd.LANDED) }
        }
    }

    /** A first position that cannot resolve falls back to the start of its resource. */
    private fun failInitialLoad(index: Int?, reason: LoadFailure) {
        if (revealed || disposed) return
        pendingNavigation?.let { endNavigation(it, NavigationEnd.TIMED_OUT) }
        val target = slots.take(index ?: 0).sumOf { it.extent }
        scrollTo(0, target)
        positionPages()
        Timber.w("load-failed reason=$reason index=$index")
        reveal()
        scheduleUpdate()
    }

    /**
     * A resource whose end is on screen has a WebView that extends above the screen. The
     * document must select its scroll anchor in the part that the reader can see.
     */
    private fun updateVisibleRegions() {
        var start = 0
        slots.forEach { slot ->
            val webView = slot.page?.webView
            if (webView != null && slot.ready && slot.measured && slot.cssViewportWidth > 0.0) {
                val hiddenTop = (scrollY - start - slot.commandedLocal).coerceIn(0, height)
                val hiddenTopCss = Math.round(hiddenTop * slot.cssViewportWidth / webView.width.coerceAtLeast(1)).toInt()
                if (hiddenTopCss != slot.hiddenTopCss) {
                    slot.hiddenTopCss = hiddenTopCss
                    webView.evaluateJavascript(
                        "document.documentElement.style.scrollPaddingTop='${hiddenTopCss}px';",
                        null
                    )
                }
            }
            start += slot.extent
        }
    }

    /** Moves the offset for a geometry change. A fling in progress keeps its motion. */
    private fun shiftOffset(dy: Int) {
        if (!flingScroller.isFinished) flingBias += dy
        scrollBy(0, dy)
    }

    /**
     * The fling runs on a scroller that this view owns, because a geometry change during the
     * fling must move its absolute positions.
     */
    override fun fling(velocityY: Int) {
        if (childCount == 0) return
        flingBias = 0
        flingScroller.fling(scrollX, scrollY, 0, velocityY, 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (flingScroller.isFinished) {
            super.computeScroll()
            return
        }
        if (flingScroller.computeScrollOffset()) {
            val step = flingStep(
                currY = flingScroller.currY,
                finalY = flingScroller.finalY,
                currVelocity = flingScroller.currVelocity,
                bias = flingBias,
                range = (column.height - height).coerceAtLeast(0)
            )
            if (step.target != scrollY) scrollTo(scrollX, step.target)
            if (step.abort) flingScroller.abortAnimation()
            postInvalidateOnAnimation()
        }
    }

    private val readerIdle: Boolean
        get() = !touchActive && !parentOwnsGesture && !coastingGesture

    private fun onChildScroll(index: Int, slot: Slot, page: R2EpubPageFragment) {
        if (slot.page !== page || writingChildScroll) return
        if (!slot.fresh && cssTransition == null && slot.ready && !slot.adjustPending) {
            slot.adjustPending = true
            measure(index, slot, page)
        }
    }

    val activeResourceIndex: Int get() = resourceAt(scrollY)

    private val extents: List<Int> get() = slots.map { it.extent }

    private fun resourceAt(globalY: Int): Int = resourceAt(extents, globalY)

    fun resourceProgression(index: Int, globalY: Int): Double =
        resourceProgression(extents, index, globalY)

    val currentPage: R2EpubPageFragment? get() = slots.getOrNull(activeResourceIndex)?.page

    fun pages(): List<R2EpubPageFragment> = slots.mapNotNull { it.page }

    fun dispose() {
        disposed = true
        flingScroller.abortAnimation()
        cancelCssTransition(CssCancel.DISPOSE)
        pendingNavigation?.ended = true
        pendingNavigation = null
        anchorCaptureGeneration++
        reflowGeneration++
        slots.forEachIndexed { index, slot -> if (slot.page != null) unmount(index, slot) }
    }

    override fun onDetachedFromWindow() {
        cancelCssTransition(CssCancel.DETACH)
        super.onDetachedFromWindow()
    }

    fun goToLocator(index: Int, locator: Locator) {
        if (disposed) return
        navigationRequested = true
        flingScroller.abortAnimation()
        val cssIsPending = cssCapturePending || cssTransition != null
        pendingNavigation?.let { endNavigation(it, NavigationEnd.SUPERSEDED) }
        parentOwnsGesture = false
        coastingGesture = false
        cachedAnchor = null
        if (!cssIsPending) anchorCaptureGeneration++
        reflowAnchor = null
        reflowGeneration++
        val request = PendingNavigation(index, locator)
        pendingNavigation = request
        postDelayed({ endNavigation(request, NavigationEnd.TIMED_OUT) }, NAVIGATION_TIMEOUT_MS)
        if (cssIsPending) {
            return
        }
        updateWindow()
        resolvePendingNavigation()
    }

    private fun invalidatePendingNavigationResolution() {
        pendingNavigation?.apply {
            queryGeneration++
            localY = null
            resolving = false
            preparedIndices = emptySet()
            landingPosted = false
        }
    }

    fun applyReadiumCss(script: String) {
        if (disposed) return
        pendingNavigation?.takeIf { it.kind == NavigationKind.REFLOW }
            ?.let { endNavigation(it, NavigationEnd.SUPERSEDED) }
        invalidatePendingNavigationResolution()
        reflowAnchor = null
        reflowGeneration++
        queuedCssScripts.add(script)
        cssTransition?.let { transition ->
            if (transition.commitReady && !transition.committed) {
                transition.commitReady = false
                runNextCssPass(transition)
            }
            return
        }
        if (cssCapturePending) return
        cssCapturePending = true
        val generation = ++cssTransitionGeneration
        postDelayed({
            if (generation == cssTransitionGeneration && (cssCapturePending || cssTransition?.generation == generation)) {
                cancelCssTransition(CssCancel.DEADLINE)
            }
        }, CSS_TRANSITION_DEADLINE_MS)
        val anchorGeneration = ++anchorCaptureGeneration
        captureAnchorAt(anchorGeneration, listOf(height / 2, height / 3, height * 2 / 3)) { anchor ->
            if (generation != cssTransitionGeneration) return@captureAnchorAt
            cssCapturePending = false
            if (anchor == null || !slots[anchor.index].ready) {
                runQueuedCssDirectly()
                return@captureAnchorAt
            }
            reflowAnchor = null
            reflowGeneration++
            val transition = CssTransition(generation, anchor)
            cssTransition = transition
            slots.forEach { it.measurementGeneration++ }
            installCssDrawGate(transition)
            runNextCssPass(transition)
        }
    }

    private fun runQueuedCssDirectly() {
        val scripts = queuedCssScripts.toList()
        queuedCssScripts.clear()
        scripts.forEach { script ->
            pages().filter { it.isLoaded.value }.forEach { it.runJavaScript(script) }
        }
    }

    private fun installCssDrawGate(transition: CssTransition) {
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (cssTransition !== transition) return@OnPreDrawListener true
            if (!transition.commitReady) return@OnPreDrawListener false
            if (!transition.committed) {
                val anchor = transition.anchor
                val measurement = transition.measurements[anchor.index]
                val targetY = measurement?.anchorY
                if (targetY == null) {
                    cancelCssTransition(CssCancel.ANCHOR_LOST)
                    return@OnPreDrawListener true
                }
                val prefix = slots.take(anchor.index).sumOf { it.extent }
                val scale = slots[anchor.index].page?.webView?.width?.toDouble()
                    ?.div(measurement.viewportWidth.coerceAtLeast(1.0)) ?: 1.0
                val globalY = (prefix + targetY + anchor.deltaCss * scale - anchor.sampleScreenY)
                    .toInt().coerceIn(0, (slots.sumOf { it.extent } - height).coerceAtLeast(0))
                scrollTo(0, globalY)
                positionPages()
                transition.committed = true
                postInvalidateOnAnimation()
                return@OnPreDrawListener false
            }
            finishCssTransition(transition)
            true
        }
        cssGateObserver = viewTreeObserver
        cssGateListener = listener
        cssGateObserver?.addOnPreDrawListener(listener)
    }

    private fun removeCssDrawGate() {
        cssGateListener?.let { listener ->
            cssGateObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        cssGateListener = null
        cssGateObserver = null
        postInvalidateOnAnimation()
    }

    private fun runNextCssPass(transition: CssTransition) {
        if (cssTransition !== transition) return
        if (queuedCssScripts.isEmpty()) {
            prepareCssCommit(transition)
            return
        }
        val script = queuedCssScripts.removeAt(0)
        val pass = ++transition.pass
        val loaded = slots.withIndex().mapNotNull { (index, slot) ->
            slot.page?.takeIf { slot.ready && it.isLoaded.value }?.webView?.let { index to it }
        }
        if (loaded.none { it.first == transition.anchor.index }) {
            cancelCssTransition(CssCancel.ANCHOR_PAGE_UNLOADED)
            return
        }
        var remaining = loaded.size
        val locatorJSON = transition.anchor.locator.toJSON().toString()
        loaded.forEach { (index, webView) ->
            val anchorQuery = if (index == transition.anchor.index) {
                "readium.resolveLocatorY($locatorJSON)"
            } else {
                "null"
            }
            val query = """(function(){
                $script
                var b=document.body,r=b.getBoundingClientRect(),a=$anchorQuery;
                return JSON.stringify({
                    h:Math.ceil((r.bottom+window.scrollY)*(${webView.width}/window.innerWidth)),
                    w:window.innerWidth,
                    y:a===null?null:a.y*(${webView.width}/window.innerWidth)
                });
            })()"""
            webView.evaluateJavascript(query) { result ->
                if (cssTransition !== transition || transition.pass != pass) return@evaluateJavascript
                try {
                    val data = JSONObject(JSONTokener(result).nextValue() as String)
                    transition.measurements[index] = CssMeasurement(
                        data.getInt("h").coerceAtLeast(1),
                        data.getDouble("w"),
                        if (data.isNull("y")) null else data.getDouble("y"),
                    )
                } catch (error: Exception) {
                    Timber.e(error, "css-transition query-error generation=${transition.generation} index=$index result=$result")
                    cancelCssTransition(CssCancel.QUERY_ERROR)
                    return@evaluateJavascript
                }
                val ready = {
                    if (cssTransition === transition && transition.pass == pass) {
                        remaining--
                        if (remaining == 0) runNextCssPass(transition)
                    }
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
                    WebViewCompat.postVisualStateCallback(webView, pass.toLong()) { ready() }
                } else {
                    webView.post { ready() }
                }
            }
        }
    }

    private fun prepareCssCommit(transition: CssTransition) {
        if (cssTransition !== transition) return
        if (transition.measurements[transition.anchor.index]?.anchorY == null) {
            cancelCssTransition(CssCancel.ANCHOR_UNRESOLVED_AFTER_CSS)
            return
        }
        transition.measurements.forEach { (index, measurement) ->
            val slot = slots[index]
            slot.extent = measurement.extent
            slot.cssViewportWidth = measurement.viewportWidth
            slot.measured = true
            slot.measurementGeneration++
            slot.frame.layoutParams = (slot.frame.layoutParams as LinearLayout.LayoutParams)
                .apply { height = measurement.extent }
        }
        geometryEpoch++
        invalidatePendingNavigationResolution()
        transition.commitReady = true
        requestLayout()
        postInvalidateOnAnimation()
    }

    private fun finishCssTransition(transition: CssTransition) {
        if (cssTransition !== transition) return
        cssTransition = null
        cssObserverIgnoreUntil = android.os.SystemClock.uptimeMillis() + CSS_REPORT_IGNORE_MS
        removeCssDrawGate()
        if (pendingNavigation != null) {
            cachedAnchor = null
            anchorCaptureGeneration++
            reflowAnchor = null
            reflowGeneration++
        } else {
            cachedAnchor = transition.anchor
            if (geometryDroppedDuringCss) {
                reflowAnchor = transition.anchor
                slots.forEachIndexed { index, slot -> slot.page?.takeIf { slot.ready }?.let { measure(index, slot, it) } }
                scheduleReflowRestore()
            }
        }
        geometryDroppedDuringCss = false
        updateVisibleRegions()
        scheduleUpdate()
        resolvePendingNavigation()
        scheduleAnchorCapture()
        if (queuedCssScripts.isNotEmpty()) {
            val pending = queuedCssScripts.toList()
            queuedCssScripts.clear()
            pending.forEach { applyReadiumCss(it) }
        }
    }

    private fun cancelCssTransition(reason: CssCancel) {
        if (!cssCapturePending && cssTransition == null && queuedCssScripts.isEmpty()) return
        cssTransitionGeneration++
        cssCapturePending = false
        cssTransition = null
        geometryDroppedDuringCss = false
        removeCssDrawGate()
        if (reason.degraded) Timber.w("css-transition cancel reason=$reason")
        if (reason.teardown) {
            queuedCssScripts.clear()
        } else {
            runQueuedCssDirectly()
            slots.forEachIndexed { index, slot -> slot.page?.takeIf { slot.ready }?.let { measure(index, slot, it) } }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldh > 0 && (cssCapturePending || cssTransition != null)) cancelCssTransition(CssCancel.VIEWPORT)
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        if (oldh > 0 && reflowAnchor == null) reflowAnchor = cachedAnchor
        geometryEpoch++
        slots.forEach { slot ->
            if (!slot.measured) {
                slot.extent = h
                slot.frame.layoutParams = (slot.frame.layoutParams as LinearLayout.LayoutParams).apply { height = h }
            }
            slot.page?.view?.layoutParams = FrameLayout.LayoutParams(-1, h)
        }
        post {
            updateWindow()
            slots.forEachIndexed { index, slot ->
                if (slot.ready) slot.page?.let { measure(index, slot, it) }
            }
            scheduleReflowRestore()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (disposed) return
        if (t != oldt) {
            scheduleIdle()
        }
        positionPages()
        scheduleUpdate()
        onPositionChanged()
        scheduleAnchorCapture()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE && touchMoving) beginReaderGesture()
        if (event.actionMasked == MotionEvent.ACTION_UP && parentOwnsGesture) {
            coastingGesture = true
            parentOwnsGesture = false
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) parentOwnsGesture = false
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scheduleIdle()
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val intercepted = super.onInterceptTouchEvent(event)
        if (intercepted && event.actionMasked == MotionEvent.ACTION_MOVE) {
            beginReaderGesture()
        }
        return intercepted
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!revealed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingScroller.abortAnimation()
                if (cssCapturePending || cssTransition != null) cancelCssTransition(CssCancel.TOUCH)
                touchStartY = event.y
                touchMoving = false
                touchActive = true
                coastingGesture = false
                settleGeneration++
            }
            MotionEvent.ACTION_MOVE -> if (!touchMoving && kotlin.math.abs(event.y - touchStartY) > touchSlop) {
                touchMoving = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchActive = false
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scheduleIdle()
        return handled
    }

    override fun requestChildFocus(child: View, focused: View) {
        val visible = Rect()
        if (focused is R2BasicWebView && focused.getGlobalVisibleRect(visible) && !visible.isEmpty) {
            skipWebViewFocusRectangle = true
            post { skipWebViewFocusRectangle = false }
            val reveal = focused.revealOnFocusHint
            focused.revealOnFocusHint = false
            try {
                super.requestChildFocus(child, focused)
            } finally {
                focused.revealOnFocusHint = reveal
            }
            return
        }
        super.requestChildFocus(child, focused)
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        if (skipWebViewFocusRectangle && child === column && rectangle.width() >= width / 2) {
            skipWebViewFocusRectangle = false
            return false
        }
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }

    private fun scheduleAnchorCapture() {
        if (pendingNavigation != null || reflowAnchor != null) return
        val generation = ++anchorCaptureGeneration
        postDelayed({
            if (generation == anchorCaptureGeneration && pendingNavigation == null && reflowAnchor == null) {
                captureAnchorAt(generation, listOf(height / 2, height / 3, height * 2 / 3))
            }
        }, ANCHOR_CAPTURE_DELAY_MS)
    }

    private fun captureAnchorAt(
        generation: Int,
        samples: List<Int>,
        completion: ((ViewportAnchor?) -> Unit)? = null,
    ) {
        val sample = samples.firstOrNull() ?: run {
            if (completion == null) cachedAnchor = null
            completion?.invoke(null)
            return
        }
        val globalY = scrollY + sample
        val index = resourceAt(globalY)
        val slot = slots[index]
        val page = slot.page
        val webView = page?.webView
        if (!slot.ready || !slot.measured || webView == null) {
            captureAnchorAt(generation, samples.drop(1), completion)
            return
        }
        val prefix = slots.take(index).sumOf { it.extent }
        val localY = globalY - prefix
        val epoch = geometryEpoch
        val script = "JSON.stringify(readium.resolveViewportAnchor($localY * window.innerWidth / ${webView.width}))"
        webView.evaluateJavascript(script) { result ->
            if (generation != anchorCaptureGeneration || scrollY + sample != globalY || geometryEpoch != epoch || slot.page !== page) {
                completion?.invoke(null)
                return@evaluateJavascript
            }
            try {
                val encoded = JSONTokener(result).nextValue() as? String
                if (encoded == null || encoded == "null") {
                    captureAnchorAt(generation, samples.drop(1), completion)
                    return@evaluateJavascript
                }
                val data = JSONObject(encoded)
                val anchor = data.getJSONObject("locator")
                val locator = Locator(
                    href = slot.resource.link.url(),
                    mediaType = slot.resource.link.mediaType ?: MediaType.XHTML,
                    locations = Locator.Locations.fromJSON(anchor.getJSONObject("locations")),
                    text = Locator.Text.fromJSON(anchor.getJSONObject("text")),
                )
                val anchorResult = ViewportAnchor(index, locator, data.getDouble("sampleY") - data.getDouble("resolvedY"), sample)
                if (completion == null) cachedAnchor = anchorResult
                completion?.invoke(anchorResult)
            } catch (error: Exception) {
                Timber.e(error, "anchor-error result=$result")
                captureAnchorAt(generation, samples.drop(1), completion)
            }
        }
    }

    private fun scheduleUpdate() {
        if (updatePosted) return
        updatePosted = true
        post {
            updatePosted = false
            updateWindow()
        }
    }

    private fun updateWindow() {
        if (disposed || height <= 0 || slots.isEmpty() || fragments.isStateSaved) return
        val request = pendingNavigation
        val required = if (request == null) {
            emptySet()
        } else {
            request.preparedIndices.ifEmpty { setOf(request.index) }
        }
        val window = liveWindow(extents, scrollY, height, required, MAX_LIVE)
        if (window.overCapacity) {
            request?.let { endNavigation(it, NavigationEnd.OVER_CAPACITY) }
            failInitialLoad(request?.index, LoadFailure.WINDOW_CAPACITY)
        }
        val wanted = window.wanted
        slots.forEachIndexed { index, slot ->
            if (index !in wanted && slot.page != null) unmount(index, slot)
        }
        slots.forEachIndexed { index, slot ->
            if (index in wanted && slot.page == null) mount(index, slot)
        }
        positionPages()
        resolvePendingNavigation()
    }

    private fun mount(index: Int, slot: Slot) {
        val page = R2EpubPageFragment.newInstance(
            slot.resource.url,
            slot.resource.link,
            positionCount = slot.resource.positionCount
        )
        slot.page = page
        fragments.beginTransaction().add(slot.frame.id, page, "continuous-$index").commitNowAllowingStateLoss()
        page.view?.layoutParams = FrameLayout.LayoutParams(-1, height)
        slot.commandedLocal = 0
        slot.hiddenTopCss = 0
        slot.fresh = true
        slot.freshLocal = -1
        slot.adjustPending = false
        page.webView?.scrollObserver = { _, _ -> onChildScroll(index, slot, page) }
        page.lifecycleScope.launch {
            page.awaitLoaded()
            if (slot.page === page) {
                slot.ready = true
                page.webView?.post {
                    if (slot.page === page) {
                        page.webView?.let { webView ->
                            webView.resourceGeometryListener = { snapshot ->
                                if (slot.page === page) onResourceGeometry(index, slot, page, snapshot)
                            }
                            webView.evaluateJavascript("readium.observeResourceGeometry(function(snapshot){Android.onResourceGeometry(JSON.stringify(snapshot));});", null)
                        }
                        measure(index, slot, page)
                    }
                }
            }
        }
    }

    private fun unmount(index: Int, slot: Slot) {
        val page = slot.page ?: return
        page.webView?.let { webView ->
            webView.resourceGeometryListener = null
            webView.scrollObserver = null
            webView.evaluateJavascript("readium.stopResourceGeometryObserver();", null)
        }
        slot.page = null
        slot.ready = false
        fragments.beginTransaction().remove(page).commitNowAllowingStateLoss()
    }

    private fun onResourceGeometry(index: Int, slot: Slot, page: R2EpubPageFragment, json: String) {
        val snapshot = parseGeometrySnapshot(json) ?: return
        if (cssTransition != null) {
            geometryDroppedDuringCss = true
            return
        }
        val scale = page.webView?.let { webView ->
            webView.width.toDouble() / slot.cssViewportWidth.coerceAtLeast(1.0)
        } ?: 1.0
        val decision = geometryDecision(
            reasons = snapshot.reasons,
            sequence = snapshot.sequence,
            extentPx = snapshot.extentCssPx * scale,
            currentExtent = slot.extent,
            visible = index in resourceAt(scrollY)..resourceAt(scrollY + height.coerceAtLeast(1) - 1),
            inCssIgnoreWindow = android.os.SystemClock.uptimeMillis() < cssObserverIgnoreUntil,
            readerIdle = readerIdle
        )
        val restore = decision.restore
        if (decision.changed) {
            if (restore && reflowAnchor == null && pendingNavigation == null) reflowAnchor = cachedAnchor
            geometryEpoch++
            pendingNavigation?.apply {
                localY = null
                preparedIndices = emptySet()
            }
            anchorCaptureGeneration++
        }
        measure(index, slot, page)
        if (restore && reflowAnchor != null) scheduleReflowRestore()
    }

    private fun scheduleReflowRestore() {
        val generation = ++reflowGeneration
        postDelayed({
            val anchor = reflowAnchor ?: return@postDelayed
            if (generation != reflowGeneration) return@postDelayed
            if (pendingNavigation != null || touchActive || parentOwnsGesture || coastingGesture) {
                reflowAnchor = null
                return@postDelayed
            }
            reflowAnchor = null
            val request = PendingNavigation(
                index = anchor.index,
                locator = anchor.locator,
                anchorDeltaCss = anchor.deltaCss,
                alignmentY = anchor.sampleScreenY,
                kind = NavigationKind.REFLOW,
            )
            pendingNavigation = request
            postDelayed({ endNavigation(request, NavigationEnd.TIMED_OUT) }, NAVIGATION_TIMEOUT_MS)
            updateWindow()
            resolvePendingNavigation()
        }, REFLOW_RESTORE_DELAY_MS)
    }

    private fun measure(index: Int, slot: Slot, page: R2EpubPageFragment) {
        if (cssTransition != null) return
        val webView = page.webView ?: return
        val measurement = ++slot.measurementGeneration
        val js = """(function(){var r=document.body.getBoundingClientRect();return JSON.stringify({h:Math.ceil((r.bottom+window.scrollY)*(${webView.width}/window.innerWidth)),innerWidth:window.innerWidth});})()"""
        webView.evaluateJavascript(js) { result ->
            if (slot.page !== page || slot.measurementGeneration != measurement) return@evaluateJavascript
            slot.adjustPending = false
            try {
                val data = JSONObject(JSONTokener(result).nextValue() as String)
                val measured = max(1, data.getInt("h"))
                slot.cssViewportWidth = data.getDouble("innerWidth")
                val oldExtent = slot.extent
                val before = resourceStart(extents, index)
                slot.extent = measured
                if (measured != oldExtent) geometryEpoch++
                slot.measured = true
                slot.frame.layoutParams = (slot.frame.layoutParams as LinearLayout.LayoutParams).apply { height = measured }
                val decision = childScrollDecision(
                    before = before,
                    oldExtent = oldExtent,
                    measured = measured,
                    scrollY = scrollY,
                    childScrollY = webView.scrollY,
                    commandedLocal = slot.commandedLocal,
                    navigationPending = pendingNavigation != null,
                    fresh = slot.fresh
                )
                when (decision) {
                    is ChildScrollDecision.ShiftBy -> shiftOffset(decision.dy)
                    ChildScrollDecision.Reject -> slot.rejectChildScroll = true
                    is ChildScrollDecision.Follow -> {
                        slot.commandedLocal = webView.scrollY
                        shiftOffset(decision.dy)
                    }
                    ChildScrollDecision.None -> Unit
                }
                if (!expectsNavigation && !navigationRequested && index == 0) reveal()
                if (revealed && readerIdle) updateVisibleRegions()
                positionPages()
                scheduleUpdate()
                resolvePendingNavigation()
                if (pendingNavigation == null && reflowAnchor == null) scheduleAnchorCapture()
            } catch (error: Exception) {
                Timber.e(error, "extent error index=$index result=$result")
            }
        }
    }

    private fun resolvePendingNavigation() {
        if (cssCapturePending || cssTransition != null) return
        val request = pendingNavigation ?: return
        if (request.localY != null) {
            prepareNavigationLanding(request)
            return
        }
        val slot = slots.getOrNull(request.index) ?: return
        val page = slot.page ?: return
        val webView = page.webView ?: return
        if (!slot.ready || !slot.measured || request.resolving) return
        request.resolving = true
        val queryGeneration = ++request.queryGeneration
        val locatorJSON = request.locator.toJSON().toString()
        val epoch = geometryEpoch
        val script = """(function(){
            const resolution=readium.resolveLocatorY($locatorJSON);
            return JSON.stringify({
              y:resolution===null?null:(resolution.y+${request.anchorDeltaCss})*(${webView.width}/window.innerWidth),
              anchored:resolution!==null
            });
        })()"""
        webView.evaluateJavascript(script) { result ->
            if (pendingNavigation !== request || slot.page !== page || request.queryGeneration != queryGeneration) return@evaluateJavascript
            if (geometryEpoch != epoch) {
                request.resolving = false
                resolvePendingNavigation()
                return@evaluateJavascript
            }
            try {
                val data = JSONObject(JSONTokener(result).nextValue() as String)
                val localY = resolvedLocalY(
                    y = if (data.isNull("y")) null else data.getDouble("y"),
                    progression = request.locator.locations.progression,
                    extent = slot.extent
                )
                if (localY == null) {
                    endNavigation(request, NavigationEnd.UNRESOLVED)
                    failInitialLoad(request.index, LoadFailure.UNRESOLVED)
                    scheduleUpdate()
                    return@evaluateJavascript
                }
                request.localY = localY
                request.anchored = data.getBoolean("anchored")
                request.resolving = false
                prepareNavigationLanding(request)
            } catch (error: Exception) {
                Timber.e(error, "navigate-error result=$result")
                endNavigation(request, NavigationEnd.UNRESOLVED)
                failInitialLoad(request.index, LoadFailure.ERROR)
            }
        }
    }

    private fun prepareNavigationLanding(request: PendingNavigation) {
        if (cssCapturePending || cssTransition != null) return
        if (pendingNavigation !== request || request.landingPosted) return
        val localY = request.localY ?: return
        val alignment = request.alignmentY ?: if (request.locator.text.highlight != null) height / 2 else 0
        val plan = landingPlan(extents, height, scrollY, request.index, localY, alignment, MAX_LIVE)
        val required = plan.required
        if (plan.overCapacity) {
            endNavigation(request, NavigationEnd.OVER_CAPACITY)
            failInitialLoad(request.index, LoadFailure.CAPACITY)
            scheduleUpdate()
            return
        }
        if (request.preparedIndices != required) {
            request.preparedIndices = required
            scheduleUpdate()
            return
        }
        if (required.any { !slots[it].ready || !slots[it].measured }) return
        if (!stageDestination(request, required, plan.globalY)) return
        request.landingPosted = true
        val epoch = geometryEpoch
        post {
            if (pendingNavigation !== request) return@post
            request.landingPosted = false
            if (cssCapturePending || cssTransition != null) return@post
            if (geometryEpoch != epoch) {
                request.localY = null
                resolvePendingNavigation()
                return@post
            }
            val finalY = landingOffset(extents, height, request.index, localY, alignment)
            pendingNavigation = null
            if (request.kind != NavigationKind.REFLOW) {
                cachedAnchor = if (request.anchored) {
                    ViewportAnchor(request.index, request.locator, request.anchorDeltaCss, alignment)
                } else {
                    null
                }
                anchorCaptureGeneration++
                reflowAnchor = null
                reflowGeneration++
            }
            slots.forEach { it.stagedLocal = null }
            scrollTo(0, finalY)
            positionPages()
            reveal()
            updateVisibleRegions()
            scheduleAnchorCapture()
            scheduleUpdate()
            endNavigation(request, NavigationEnd.LANDED)
        }
    }

    /**
     * A WebView that is not on screen can drop a scroll position that the view sets. The
     * document takes the destination position first, and the landing waits for it.
     */
    private fun stageDestination(request: PendingNavigation, required: Set<Int>, globalY: Int): Boolean {
        if (request.stagedEpoch == geometryEpoch && request.stagedY == globalY) return request.stageRemaining == 0
        val epoch = geometryEpoch
        request.stagedEpoch = epoch
        request.stagedY = globalY
        val onScreen = if (revealed) activeResourceIndex..resourceAt(scrollY + height - 1) else IntRange.EMPTY
        val targets = required.filter { it !in onScreen && slots[it].page?.webView != null }
        request.stageRemaining = targets.size
        targets.forEach { index ->
            val slot = slots[index]
            val webView = slot.page?.webView ?: return@forEach
            val start = slots.take(index).sumOf { it.extent }
            val local = (globalY - start).coerceIn(0, max(0, slot.extent - height))
            slot.stagedLocal = local
            val css = local * slot.cssViewportWidth / webView.width.coerceAtLeast(1)
            webView.evaluateJavascript("(function(){window.scrollTo(0,$css);return window.scrollY;})()") { result ->
                if (pendingNavigation !== request || request.stagedEpoch != epoch || request.stagedY != globalY) return@evaluateJavascript
                if (--request.stageRemaining == 0) prepareNavigationLanding(request)
            }
        }
        positionPages()
        return request.stageRemaining == 0
    }

    private fun positionPages() {
        if (height <= 0) return
        var start = 0
        slots.forEachIndexed { index, slot ->
            val page = slot.page
            if (page != null) {
                val local = slot.stagedLocal?.takeIf { pendingNavigation != null }
                    ?: (scrollY - start).coerceIn(0, max(0, slot.extent - height))
                val childLeads = pendingNavigation == null && !slot.fresh &&
                    cssTransition == null && slot.ready && !slot.rejectChildScroll &&
                    page.webView?.let { it.scrollY != slot.commandedLocal } == true
                if (childLeads) {
                    if (!slot.adjustPending) {
                        slot.adjustPending = true
                        measure(index, slot, page)
                    }
                    start += slot.extent
                    return@forEachIndexed
                }
                page.view?.translationY = local.toFloat()
                page.webView?.let { webView ->
                    if (webView.scrollY != local) {
                        writingChildScroll = true
                        try {
                            webView.scrollTo(0, local)
                        } finally {
                            writingChildScroll = false
                        }
                    }
                    slot.commandedLocal = webView.scrollY
                    slot.rejectChildScroll = false
                    val onScreen = revealed && start < scrollY + height && start + slot.extent > scrollY
                    if (slot.fresh && slot.ready && slot.measured) {
                        if (local == 0) {
                            if (onScreen) slot.fresh = false
                        } else if (slot.freshLocal != local) {
                            // A view-side position on a WebView that was not drawn can be dropped.
                            slot.freshLocal = local
                            val css = local * slot.cssViewportWidth / webView.width.coerceAtLeast(1)
                            webView.evaluateJavascript("window.scrollTo(0,$css);") {
                                if (slot.page === page && slot.freshLocal == local) slot.fresh = false
                            }
                        }
                    }
                }
            }
            start += slot.extent
        }
    }

    companion object {
        private const val MAX_LIVE = 6
        private const val CSS_TRANSITION_DEADLINE_MS = 1000L
        private const val LOAD_TIMEOUT_MS = 10_000L

        /** A navigation that has not landed after this long ends without a landing. */
        private const val NAVIGATION_TIMEOUT_MS = 10_000L

        /** Without a scroll change for this long, a gesture or a fling is over. */
        private const val IDLE_DELAY_MS = 250L

        /** The text at the viewport centre is captured this long after the last scroll change. */
        private const val ANCHOR_CAPTURE_DELAY_MS = 100L

        /** Layout reports that arrive within this time are restored as one change. */
        private const val REFLOW_RESTORE_DELAY_MS = 180L

        /** A style report within this time of a preference change repeats that change. */
        private const val CSS_REPORT_IGNORE_MS = 500L
    }
}

private enum class NavigationKind {
    /** A request of the navigator's owner. */
    JUMP,

    /** The surface puts the reading position back after a layout change. */
    REFLOW,
}

/**
 * Why a preference transaction ends before its commit.
 *
 * @param teardown The surface goes away; queued style changes are dropped, not applied.
 * @param degraded The style change is applied without keeping the reading position, for a
 * reason other than the reader's own action.
 */
private enum class CssCancel(val teardown: Boolean = false, val degraded: Boolean = false) {
    DISPOSE(teardown = true),
    DETACH(teardown = true),
    TOUCH,
    VIEWPORT,
    DEADLINE(degraded = true),
    ANCHOR_LOST(degraded = true),
    ANCHOR_PAGE_UNLOADED(degraded = true),
    ANCHOR_UNRESOLVED_AFTER_CSS(degraded = true),
    QUERY_ERROR,
}

/**
 * How a navigation ends.
 *
 * @param fault The request could not be honored, for a reason other than a newer request or
 * the reader's own scroll.
 */
private enum class NavigationEnd(val fault: Boolean = false) {
    LANDED,
    SUPERSEDED,
    UNRESOLVED(fault = true),
    TIMED_OUT(fault = true),
    OVER_CAPACITY(fault = true),
}

private enum class LoadFailure { TIMEOUT, WINDOW_CAPACITY, UNRESOLVED, ERROR, CAPACITY }
