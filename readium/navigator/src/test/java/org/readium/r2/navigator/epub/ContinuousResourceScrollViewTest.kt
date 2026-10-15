/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import android.view.View
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.pager.R2PagerAdapter.PageResource.EpubReflowable
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class ContinuousResourceScrollViewTest {

    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private val shown = mutableListOf<Unit>()
    private val ended = mutableListOf<Pair<Locator, Boolean>>()
    private var onEnded: (Locator, Boolean) -> Unit = { _, _ -> }

    // Robolectric does not lay the surface out, so its height stays 0 and no page is mounted:
    // a navigation never resolves.
    private fun surface(resourceCount: Int = 0) = ContinuousResourceScrollView(
        context = activity,
        fragments = activity.supportFragmentManager,
        resources = List(resourceCount) { index ->
            EpubReflowable(
                link = Link(href = Href("chapter$index.xhtml")!!),
                url = AbsoluteUrl("https://readium.test/chapter$index.xhtml")!!,
                positionCount = 1
            )
        },
        expectsNavigation = true,
        onPositionChanged = {},
        onFirstPositionShown = { shown += Unit },
        onNavigationEnded = { locator, landed ->
            ended += locator to landed
            onEnded(locator, landed)
        }
    ).also { activity.setContentView(it) }

    private fun locator(index: Int) = Locator(
        href = Url("chapter$index.xhtml")!!,
        mediaType = MediaType.XHTML
    )

    private val ContinuousResourceScrollView.content: View get() = getChildAt(0)

    @Test
    fun `a first position that does not arrive reveals the surface after the timeout`() {
        val surface = surface()
        assertEquals(View.INVISIBLE, surface.content.visibility)

        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS)

        assertEquals(View.VISIBLE, surface.content.visibility)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a disposed surface does not act on its timeout`() {
        val surface = surface()
        surface.dispose()

        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS)

        assertEquals(View.INVISIBLE, surface.content.visibility)
        assertEquals(0, shown.size)
    }

    @Test
    fun `a navigation that does not land ends after the timeout`() {
        val surface = surface(resourceCount = 3)
        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS)
        assertEquals(1, shown.size)

        surface.goToLocator(2, locator(2))
        ShadowLooper.idleMainLooper(9, TimeUnit.SECONDS)
        assertEquals(emptyList(), ended)

        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertEquals(listOf(locator(2) to false), ended)
    }

    @Test
    fun `a navigation that is pending at the first timeout ends with it`() {
        val surface = surface(resourceCount = 3)
        surface.goToLocator(2, locator(2))

        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS)

        assertEquals(listOf(locator(2) to false), ended)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a later navigation ends the earlier one`() {
        val surface = surface(resourceCount = 3)

        surface.goToLocator(1, locator(1))
        surface.goToLocator(2, locator(2))
        ShadowLooper.idleMainLooper()

        assertEquals(listOf(locator(1) to false), ended)
    }

    @Test
    fun `a navigation made from the listener is the latest one`() {
        lateinit var surface: ContinuousResourceScrollView
        var redirected = false
        onEnded = { _, _ ->
            if (!redirected) {
                redirected = true
                surface.goToLocator(0, locator(0))
            }
        }
        surface = surface(resourceCount = 3)

        surface.goToLocator(1, locator(1))
        surface.goToLocator(2, locator(2))
        ShadowLooper.idleMainLooper()

        // The request from the listener replaces the one that was pending when it ran.
        assertEquals(listOf(locator(1) to false, locator(2) to false), ended)
    }

    @Test
    fun `a navigation is reported one time`() {
        val surface = surface(resourceCount = 3)
        surface.goToLocator(1, locator(1))
        surface.goToLocator(2, locator(2))

        ShadowLooper.idleMainLooper(25, TimeUnit.SECONDS)

        assertEquals(listOf(locator(1) to false, locator(2) to false), ended)
    }

    @Test
    fun `a disposed surface reports no navigation`() {
        val surface = surface(resourceCount = 3)
        surface.goToLocator(1, locator(1))
        surface.dispose()

        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS)

        assertEquals(emptyList(), ended)
    }
}
