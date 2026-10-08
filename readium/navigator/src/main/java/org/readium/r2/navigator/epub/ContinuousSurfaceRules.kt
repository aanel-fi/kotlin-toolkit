/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import kotlin.math.abs
import org.json.JSONException
import org.json.JSONObject

/*
 * The decision rules of [ContinuousResourceScrollView], as functions of plain values.
 *
 * Offsets and extents are in view pixels. A global offset is measured from the top of the first
 * resource; a local offset from the top of one resource.
 */

/** Index of the resource that contains [globalY]. An offset past the end maps to the last one. */
internal fun resourceAt(extents: List<Int>, globalY: Int): Int {
    var start = 0
    for (index in extents.indices) {
        if (globalY < start + extents[index]) return index
        start += extents[index]
    }
    return extents.lastIndex.coerceAtLeast(0)
}

/** Global offset of the top of the resource at [index]. */
internal fun resourceStart(extents: List<Int>, index: Int): Int =
    extents.take(index).sum()

/** The largest global offset a viewport of [height] can scroll to. */
internal fun maxOffset(extents: List<Int>, height: Int): Int =
    (extents.sum() - height).coerceAtLeast(0)

/** Progression of [globalY] inside the resource at [index], from 0 to 1. */
internal fun resourceProgression(extents: List<Int>, index: Int, globalY: Int): Double =
    ((globalY - resourceStart(extents, index)).toDouble() / extents[index].coerceAtLeast(1))
        .coerceIn(0.0, 1.0)

/**
 * The resources to keep mounted.
 *
 * @param overCapacity The visible range and [required] together exceed the cap. [wanted] then
 * holds the visible range without [required].
 */
internal data class LiveWindow(val wanted: Set<Int>, val overCapacity: Boolean)

/**
 * Chooses the resources to mount for a viewport at [scrollY]: the visible range, the resources a
 * pending navigation [required], and one neighbor on each side of the visible range while the
 * window is below [maxLive].
 */
internal fun liveWindow(
    extents: List<Int>,
    scrollY: Int,
    height: Int,
    required: Set<Int>,
    maxLive: Int,
): LiveWindow {
    val first = resourceAt(extents, scrollY)
    var end = 0
    var lastVisible = first
    extents.forEachIndexed { index, extent ->
        val start = end
        end += extent
        if (start < scrollY + height && end > scrollY) lastVisible = index
    }
    val visible = (first..lastVisible).toSet()
    val overCapacity = (visible + required).size > maxLive
    val wanted = (visible + if (overCapacity) emptySet() else required).toMutableSet()
    if (wanted.size < maxLive && first > 0) wanted.add(first - 1)
    if (wanted.size < maxLive && lastVisible < extents.lastIndex) wanted.add(lastVisible + 1)
    return LiveWindow(wanted, overCapacity)
}

/**
 * Where a navigation lands.
 *
 * @param globalY The offset of the viewport top after the landing.
 * @param required The resources on the screen after the landing, and the target resource.
 * @param overCapacity The resources on the screen now and [required] together exceed the cap.
 */
internal data class LandingPlan(val globalY: Int, val required: Set<Int>, val overCapacity: Boolean)

/**
 * Plans a landing that puts the offset [localY] of the resource at [index] on the line
 * [alignment] pixels below the viewport top.
 */
internal fun landingPlan(
    extents: List<Int>,
    height: Int,
    scrollY: Int,
    index: Int,
    localY: Double,
    alignment: Int,
    maxLive: Int,
): LandingPlan {
    val globalY = landingOffset(extents, height, index, localY, alignment)
    val first = resourceAt(extents, globalY)
    val last = resourceAt(extents, (globalY + height - 1).coerceAtLeast(globalY))
    val required = (first..last).toSet() + index
    val current = (resourceAt(extents, scrollY)..resourceAt(extents, scrollY + height - 1)).toSet()
    return LandingPlan(globalY, required, (current + required).size > maxLive)
}

/** The offset of the viewport top for a landing; see [landingPlan]. */
internal fun landingOffset(
    extents: List<Int>,
    height: Int,
    index: Int,
    localY: Double,
    alignment: Int,
): Int =
    (resourceStart(extents, index) + localY.toInt() - alignment)
        .coerceIn(0, maxOffset(extents, height))

/** One frame of a fling: the offset to show, and whether the fling ends at a book end. */
internal data class FlingStep(val target: Int, val abort: Boolean)

/**
 * Applies [bias], the geometry change since the fling started, to the scroller's position, and
 * ends a fling whose destination lies past the first or the last offset.
 */
internal fun flingStep(currY: Int, finalY: Int, currVelocity: Float, bias: Int, range: Int): FlingStep {
    val target = (currY + bias).coerceIn(0, range)
    val abort = (target == 0 && currVelocity > 0f && finalY + bias < 0) ||
        (target == range && finalY + bias > range)
    return FlingStep(target, abort)
}

/** What the surface does when a resource reports a new extent and its own scroll position. */
internal sealed interface ChildScrollDecision {
    /** The resource is above the viewport: move the offset by its extent change. */
    data class ShiftBy(val dy: Int) : ChildScrollDecision

    /** The scroll change is not a layout adjustment: write the commanded position back. */
    data object Reject : ChildScrollDecision

    /** The document kept its own position across a layout change: follow it. */
    data class Follow(val dy: Int) : ChildScrollDecision

    data object None : ChildScrollDecision
}

/**
 * @param before Global offset of the resource's top.
 * @param oldExtent Its extent before the measurement, [measured] after it.
 * @param childScrollY The document's own scroll position, [commandedLocal] the one the surface
 * last wrote.
 * @param fresh The page has not taken its first commanded position yet.
 */
internal fun childScrollDecision(
    before: Int,
    oldExtent: Int,
    measured: Int,
    scrollY: Int,
    childScrollY: Int,
    commandedLocal: Int,
    navigationPending: Boolean,
    fresh: Boolean,
): ChildScrollDecision {
    val delta = measured - oldExtent
    val childAdjust = childScrollY - commandedLocal
    return when {
        before + oldExtent <= scrollY && delta != 0 ->
            ChildScrollDecision.ShiftBy(delta)
        childAdjust != 0 && (
            delta == 0 || (childAdjust > 0) != (delta > 0) || abs(childAdjust) > abs(delta)
            ) ->
            ChildScrollDecision.Reject
        !navigationPending && !fresh && childAdjust != 0 &&
            before <= scrollY && scrollY < before + oldExtent ->
            ChildScrollDecision.Follow(childAdjust)
        else ->
            ChildScrollDecision.None
    }
}

/**
 * How the surface treats a geometry report of a page.
 *
 * @param changed The layout of the page changed after its first report.
 * @param restore The surface puts the reading position back after the change.
 */
internal data class GeometryDecision(val changed: Boolean, val restore: Boolean)

internal const val GEOMETRY_REASON_INITIAL: String = "initial"
internal const val GEOMETRY_REASON_CSS: String = "css"

/** An extent change of this many pixels or fewer is rounding, not a layout change. */
internal const val EXTENT_TOLERANCE_PX: Double = 1.0

/**
 * @param extentPx The reported extent in view pixels, [currentExtent] the one the surface holds.
 * @param visible The page is in the viewport.
 * @param inCssIgnoreWindow A preference change was applied a moment ago, so a style report
 * without an extent change repeats it.
 */
internal fun geometryDecision(
    reasons: Set<String>,
    sequence: Int,
    extentPx: Double,
    currentExtent: Int,
    visible: Boolean,
    inCssIgnoreWindow: Boolean,
    readerIdle: Boolean,
): GeometryDecision {
    val extentChanged = abs(extentPx - currentExtent) > EXTENT_TOLERANCE_PX
    val initial = GEOMETRY_REASON_INITIAL in reasons || sequence == 1
    val changed = !initial && (GEOMETRY_REASON_CSS in reasons || extentChanged)
    val redundantCss = GEOMETRY_REASON_CSS in reasons && !extentChanged && inCssIgnoreWindow
    return GeometryDecision(changed, restore = changed && visible && !redundantCss && readerIdle)
}

/**
 * The local offset of a navigation target: [y] when the page resolved the locator to a range,
 * else the locator's [progression] of the resource's [extent]. Null when it has neither.
 */
internal fun resolvedLocalY(y: Double?, progression: Double?, extent: Int): Double? =
    y ?: progression?.let { extent * it }

/**
 * The y coordinate, in the CSS pixels of a page's own viewport, of the point [viewportY] view
 * pixels below the top of the surface, for a page whose view starts [pageTop] view pixels below
 * it. Null while the page's layout viewport is not known.
 */
internal fun pageClientY(viewportY: Int, pageTop: Int, cssViewportWidth: Double, viewWidth: Int): Double? {
    if (cssViewportWidth <= 0.0 || viewWidth <= 0) return null
    return (viewportY - pageTop) * cssViewportWidth / viewWidth
}

/** A page's report of its layout, from the reflowable script's geometry observer. */
internal data class GeometrySnapshot(val reasons: Set<String>, val sequence: Int, val extentCssPx: Double)

/** The script's own report is a fraction of this; a longer one is not parsed. */
internal const val MAX_GEOMETRY_SNAPSHOT_LENGTH: Int = 2048

private const val MAX_GEOMETRY_REASONS = 8
private const val MAX_EXTENT_CSS_PX = 10_000_000.0

/**
 * Reads a geometry report. The report arrives through a JavaScript interface that the
 * publication's own scripts can call, so anything that is not the observer's shape is dropped:
 * null is returned.
 */
internal fun parseGeometrySnapshot(json: String): GeometrySnapshot? {
    if (json.length > MAX_GEOMETRY_SNAPSHOT_LENGTH) return null
    return try {
        val data = JSONObject(json)
        val reasons = data.getJSONArray("reasons")
        if (reasons.length() > MAX_GEOMETRY_REASONS) return null
        val reasonSet = (0 until reasons.length()).map { reasons.get(it) as? String ?: return null }.toSet()
        val sequence = data.get("sequence") as? Int ?: return null
        val extent = (data.get("extentCssPx") as? Number)?.toDouble() ?: return null
        if (sequence < 1 || !extent.isFinite() || extent < 0.0 || extent > MAX_EXTENT_CSS_PX) return null
        GeometrySnapshot(reasonSet, sequence, extent)
    } catch (error: JSONException) {
        null
    }
}
