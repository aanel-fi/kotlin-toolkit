/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ContinuousSurfaceRulesTest {

    private val mixed = listOf(1000, 500, 2000)

    @Test
    fun `an offset maps to the resource that contains it`() {
        assertEquals(
            listOf(0, 0, 1, 1, 2, 2, 2, 0),
            listOf(0, 999, 1000, 1499, 1500, 3500, 99999, -5).map { resourceAt(mixed, it) }
        )
    }

    @Test
    fun `an empty reading order maps every offset to index 0`() {
        assertEquals(0, resourceAt(emptyList(), 0))
        assertEquals(0, maxOffset(emptyList(), 800))
    }

    @Test
    fun `a resource starts after the extents before it`() {
        assertEquals(listOf(0, 1000, 1500), listOf(0, 1, 2).map { resourceStart(mixed, it) })
    }

    @Test
    fun `the last offset leaves one viewport of content`() {
        assertEquals(2700, maxOffset(mixed, 800))
        assertEquals(0, maxOffset(listOf(300), 800))
    }

    @Test
    fun `progression is the offset inside the resource, clamped`() {
        assertEquals(0.5, resourceProgression(mixed, 1, 1250))
        assertEquals(0.0, resourceProgression(mixed, 1, 900))
        assertEquals(1.0, resourceProgression(mixed, 1, 1600))
        assertEquals(0.0, resourceProgression(listOf(0), 0, 0))
    }

    @Test
    fun `the live window is the visible range and one neighbor on each side`() {
        val extents = List(4) { 1000 }
        assertEquals(setOf(0, 1), liveWindow(extents, 0, 800, emptySet(), 6))
        assertEquals(setOf(0, 1, 2), liveWindow(extents, 500, 800, emptySet(), 6))
        assertEquals(setOf(0, 1, 2), liveWindow(extents, 1000, 800, emptySet(), 6))
        assertEquals(setOf(2, 3), liveWindow(extents, 3200, 800, emptySet(), 6))
    }

    @Test
    fun `the live window holds the resources a navigation requires`() {
        assertEquals(
            setOf(0, 1, 3),
            liveWindow(List(4) { 1000 }, 0, 800, setOf(3), 6)
        )
    }

    @Test
    fun `a full live window gets no neighbors`() {
        assertEquals(
            setOf(0, 1, 2, 3, 6, 7),
            liveWindow(List(8) { 200 }, 0, 800, setOf(6, 7), 6)
        )
    }

    @Test
    fun `the resources a navigation requires are mounted with the visible range, past the cap`() {
        assertEquals(
            setOf(0, 1, 2, 3, 5, 6, 7),
            liveWindow(List(8) { 200 }, 0, 800, setOf(5, 6, 7), 6)
        )
    }

    @Test
    fun `a visible range larger than the cap stays whole`() {
        assertEquals(
            (0..7).toSet(),
            liveWindow(List(8) { 100 }, 0, 800, emptySet(), 6)
        )
    }

    @Test
    fun `a landing puts the target offset at the alignment line`() {
        val extents = List(10) { 1000 }
        assertEquals(LandingPlan(5300, setOf(5, 6)), landingPlan(extents, 800, 5, 300.9, 0))
        assertEquals(LandingPlan(4900, setOf(4, 5)), landingPlan(extents, 800, 5, 300.9, 400))
    }

    @Test
    fun `a target that fits in the viewport lands at the middle, a taller one at the top`() {
        assertEquals(400, landingAlignment(explicit = null, centred = true, height = 800))
        assertEquals(0, landingAlignment(explicit = null, centred = false, height = 800))
        assertEquals(120, landingAlignment(explicit = 120, centred = true, height = 800))
    }

    @Test
    fun `a landing is clamped to the last offset`() {
        assertEquals(
            LandingPlan(9200, setOf(9)),
            landingPlan(List(10) { 1000 }, 800, 9, 900.0, 0)
        )
    }

    @Test
    fun `a landing among short resources requires every resource it shows`() {
        assertEquals(
            LandingPlan(2000, setOf(10, 11, 12, 13)),
            landingPlan(List(20) { 200 }, 800, 10, 0.0, 0)
        )
    }

    @Test
    fun `a fling inside the range continues`() {
        assertEquals(FlingStep(100, false), flingStep(100, -300, 1200f, 0, 5000))
        assertEquals(FlingStep(0, false), flingStep(0, 0, 0f, 0, 5000))
        assertEquals(FlingStep(5000, false), flingStep(5000, 5000, 0f, 0, 5000))
    }

    @Test
    fun `a fling that would pass a book end stops there`() {
        assertEquals(FlingStep(0, true), flingStep(-20, -300, 900f, 0, 5000))
        assertEquals(FlingStep(5000, true), flingStep(5100, 5600, 0f, 0, 5000))
        assertEquals(FlingStep(5000, true), flingStep(4900, 4990, 0f, 200, 5000))
    }

    @Test
    fun `a change above the viewport shifts the offset by the extent change`() {
        assertEquals(
            ChildScrollDecision.ShiftBy(200),
            childScrollDecision(0, 1000, 1200, 1500, 0, 0, false, false)
        )
        assertEquals(
            ChildScrollDecision.ShiftBy(200),
            childScrollDecision(0, 1000, 1200, 1000, 0, 0, false, false)
        )
    }

    @Test
    fun `a child scroll within its extent change is followed`() {
        assertEquals(
            ChildScrollDecision.Follow(60),
            childScrollDecision(1000, 2000, 2100, 1500, 560, 500, false, false)
        )
        assertEquals(
            ChildScrollDecision.Follow(100),
            childScrollDecision(1000, 2000, 2100, 1500, 600, 500, false, false)
        )
    }

    @Test
    fun `a child scroll without a matching extent change is rejected`() {
        assertEquals(
            ChildScrollDecision.Reject,
            childScrollDecision(1000, 2000, 2000, 1500, 520, 500, false, false)
        )
        assertEquals(
            ChildScrollDecision.Reject,
            childScrollDecision(1000, 2000, 2100, 1500, 650, 500, false, false)
        )
        assertEquals(
            ChildScrollDecision.Reject,
            childScrollDecision(1000, 2000, 2100, 1500, 440, 500, false, false)
        )
    }

    @Test
    fun `a child scroll is not followed during a navigation or on a fresh page`() {
        assertEquals(
            ChildScrollDecision.None,
            childScrollDecision(1000, 2000, 2100, 1500, 560, 500, true, false)
        )
        assertEquals(
            ChildScrollDecision.None,
            childScrollDecision(1000, 2000, 2100, 1500, 560, 500, false, true)
        )
        assertEquals(
            ChildScrollDecision.None,
            childScrollDecision(1000, 2000, 2000, 1500, 500, 500, false, false)
        )
    }

    @Test
    fun `a child scroll to the accessibility focus is followed`() {
        // The viewport top is in the resource.
        assertEquals(
            ChildScrollDecision.Follow(700),
            childScrollDecision(1000, 2000, 2000, 1500, 1200, 500, false, false, showsAccessibilityFocus = true)
        )
        assertEquals(
            ChildScrollDecision.Follow(-300),
            childScrollDecision(1000, 2000, 2000, 1500, 200, 500, false, false, showsAccessibilityFocus = true)
        )
        // The resource starts in the viewport.
        assertEquals(
            ChildScrollDecision.Follow(400),
            childScrollDecision(1000, 2000, 2000, 600, 400, 0, false, false, showsAccessibilityFocus = true)
        )
        // The resource is wholly above the viewport.
        assertEquals(
            ChildScrollDecision.Follow(-900),
            childScrollDecision(1000, 2000, 2000, 3500, 300, 1200, false, false, showsAccessibilityFocus = true)
        )
    }

    @Test
    fun `a child scroll to the accessibility focus is not followed during a navigation or on a fresh page`() {
        assertEquals(
            ChildScrollDecision.Reject,
            childScrollDecision(1000, 2000, 2000, 1500, 1200, 500, true, false, showsAccessibilityFocus = true)
        )
        assertEquals(
            ChildScrollDecision.Reject,
            childScrollDecision(1000, 2000, 2000, 1500, 1200, 500, false, true, showsAccessibilityFocus = true)
        )
    }

    @Test
    fun `an extent change keeps its rule while a node has accessibility focus`() {
        assertEquals(
            ChildScrollDecision.ShiftBy(200),
            childScrollDecision(0, 1000, 1200, 1500, 0, 0, false, false, showsAccessibilityFocus = true)
        )
        assertEquals(
            ChildScrollDecision.Follow(60),
            childScrollDecision(1000, 2000, 2100, 1500, 560, 500, false, false, showsAccessibilityFocus = true)
        )
        assertEquals(
            ChildScrollDecision.None,
            childScrollDecision(1000, 2000, 2000, 1500, 500, 500, false, false, showsAccessibilityFocus = true)
        )
    }

    @Test
    fun `a document scroll larger than an extent change is rejected while a node has accessibility focus`() {
        assertEquals(
            ChildScrollDecision.Reject,
            childScrollDecision(1000, 2000, 2100, 1500, 650, 500, false, false, showsAccessibilityFocus = true)
        )
    }

    @Test
    fun `a resource above the viewport moves the offset by its extent change while a node has accessibility focus`() {
        assertEquals(
            ChildScrollDecision.ShiftBy(200),
            childScrollDecision(0, 1000, 1200, 1500, 50, 0, false, false, showsAccessibilityFocus = true)
        )
    }

    @Test
    fun `the focus window is open for the page whose node took focus`() {
        val window = AccessibilityFocusWindow()
        assertFalse(window.isOpenFor(2, 0))
        window.nodeFocused(2, 1000)
        assertTrue(window.isOpenFor(2, 1000))
        assertFalse(window.isOpenFor(1, 1000))
        assertFalse(window.isOpenFor(3, 1000))
        assertFalse(window.isOpenFor(-1, 1000))
    }

    @Test
    fun `the focus window closes after its time limit`() {
        val window = AccessibilityFocusWindow()
        window.nodeFocused(2, 1000)
        assertTrue(window.isOpenFor(2, 1000 + ACCESSIBILITY_FOCUS_SCROLL_MS - 1))
        assertFalse(window.isOpenFor(2, 1000 + ACCESSIBILITY_FOCUS_SCROLL_MS))
    }

    @Test
    fun `a new focus moves the focus window to its page`() {
        val window = AccessibilityFocusWindow()
        window.nodeFocused(2, 1000)
        window.nodeFocused(3, 1500)
        assertFalse(window.isOpenFor(2, 1500))
        assertTrue(window.isOpenFor(3, 1500 + ACCESSIBILITY_FOCUS_SCROLL_MS - 1))
    }

    @Test
    fun `the focus window closes at the first scroll it follows`() {
        val window = AccessibilityFocusWindow().apply { nodeFocused(2, 1000) }
        window.followed()
        assertFalse(window.isOpenFor(2, 1001))
    }

    @Test
    fun `the focus window closes at a reader gesture`() {
        val window = AccessibilityFocusWindow().apply { nodeFocused(2, 1000) }
        window.readerGesture()
        assertFalse(window.isOpenFor(2, 1001))
    }

    @Test
    fun `the focus window closes when a navigation starts`() {
        val window = AccessibilityFocusWindow().apply { nodeFocused(2, 1000) }
        window.navigationStarted()
        assertFalse(window.isOpenFor(2, 1001))
    }

    @Test
    fun `the focus window closes when focus goes to a document's root node`() {
        val window = AccessibilityFocusWindow().apply { nodeFocused(2, 1000) }
        window.rootFocused()
        assertFalse(window.isOpenFor(2, 1001))
    }

    @Test
    fun `the focus window closes when the surface moves to show the focus`() {
        val window = AccessibilityFocusWindow().apply { nodeFocused(2, 1000) }
        window.shownBySurface()
        assertFalse(window.isOpenFor(2, 1001))
    }

    @Test
    fun `a focused node cut by the lower edge moves to the middle of the band`() {
        // A paragraph that starts in the lower half and continues below the edge.
        assertEquals(658, accessibilityFocusShift(nodeTop = 1858, nodeBottom = 2400, bandTop = 0, bandBottom = 2400))
        // A strip of a paragraph at the edge.
        assertEquals(1160, accessibilityFocusShift(nodeTop = 2360, nodeBottom = 2400, bandTop = 0, bandBottom = 2400))
        // The band does not start at the screen's top.
        assertEquals(200, accessibilityFocusShift(nodeTop = 1500, nodeBottom = 2500, bandTop = 100, bandBottom = 2500))
    }

    @Test
    fun `a focused node that the reader sees or that the WebView scrolls to does not move the offset`() {
        // Wholly in the band.
        assertEquals(0, accessibilityFocusShift(nodeTop = 1858, nodeBottom = 2300, bandTop = 0, bandBottom = 2400))
        // It starts in the upper half: the reader reads it from its start.
        assertEquals(0, accessibilityFocusShift(nodeTop = 600, nodeBottom = 2400, bandTop = 0, bandBottom = 2400))
        // Its top is below or above the band: the WebView scrolls to it.
        assertEquals(0, accessibilityFocusShift(nodeTop = 2400, nodeBottom = 2400, bandTop = 0, bandBottom = 2400))
        assertEquals(0, accessibilityFocusShift(nodeTop = -200, nodeBottom = 300, bandTop = 0, bandBottom = 2400))
    }

    @Test
    fun `the hidden bottom is the part of a page's view below the viewport`() {
        // The resource starts 600 px below the viewport top; its view starts with it.
        assertEquals(600, hiddenBottom(start = 1600, commandedLocal = 0, scrollY = 1000, viewportHeight = 2400))
        // The view follows the viewport inside the resource.
        assertEquals(0, hiddenBottom(start = 0, commandedLocal = 1000, scrollY = 1000, viewportHeight = 2400))
        // The resource ends in the viewport: the view extends above it, not below.
        assertEquals(0, hiddenBottom(start = 0, commandedLocal = 600, scrollY = 1000, viewportHeight = 2400))
        // Wholly below the viewport.
        assertEquals(2400, hiddenBottom(start = 9000, commandedLocal = 0, scrollY = 1000, viewportHeight = 2400))
    }

    @Test
    fun `the first geometry report of a page changes nothing`() {
        assertEquals(
            GeometryDecision(changed = false, restore = false),
            geometryDecision(setOf("initial"), 1, 1500.0, 1000, true, false, true)
        )
        assertEquals(
            GeometryDecision(changed = false, restore = false),
            geometryDecision(setOf("initial", "css"), 3, 1500.0, 1000, true, false, true)
        )
        assertEquals(
            GeometryDecision(changed = false, restore = false),
            geometryDecision(setOf("resize"), 1, 1500.0, 1000, true, false, true)
        )
    }

    @Test
    fun `an extent within one pixel is not a change`() {
        assertEquals(
            GeometryDecision(changed = false, restore = false),
            geometryDecision(setOf("resize"), 5, 1000.5, 1000, true, false, true)
        )
    }

    @Test
    fun `a visible change restores the position only while the reader is idle`() {
        assertEquals(
            GeometryDecision(changed = true, restore = true),
            geometryDecision(setOf("resize"), 5, 1002.0, 1000, true, false, true)
        )
        assertEquals(
            GeometryDecision(changed = true, restore = false),
            geometryDecision(setOf("resize"), 5, 1002.0, 1000, true, false, false)
        )
        assertEquals(
            GeometryDecision(changed = true, restore = false),
            geometryDecision(setOf("resize"), 5, 1002.0, 1000, false, false, true)
        )
    }

    @Test
    fun `a style report without an extent change is redundant inside the ignore window`() {
        assertEquals(
            GeometryDecision(changed = true, restore = false),
            geometryDecision(setOf("css"), 5, 1000.0, 1000, true, true, true)
        )
        assertEquals(
            GeometryDecision(changed = true, restore = true),
            geometryDecision(setOf("css"), 5, 1000.0, 1000, true, false, true)
        )
        assertEquals(
            GeometryDecision(changed = true, restore = true),
            geometryDecision(setOf("css"), 5, 1200.0, 1000, true, true, true)
        )
    }

    @Test
    fun `a viewport position maps to the page's own client coordinates`() {
        // A page of 1080 view pixels shows a layout viewport of 360 CSS pixels.
        assertEquals(100.0, pageClientY(viewportY = 900, pageTop = 600, cssViewportWidth = 360.0, viewWidth = 1080))
        assertEquals(0.0, pageClientY(viewportY = 600, pageTop = 600, cssViewportWidth = 360.0, viewWidth = 1080))
        assertEquals(-50.0, pageClientY(viewportY = 450, pageTop = 600, cssViewportWidth = 360.0, viewWidth = 1080))
    }

    @Test
    fun `a page that is not measured has no client coordinates`() {
        assertNull(pageClientY(viewportY = 900, pageTop = 600, cssViewportWidth = 0.0, viewWidth = 1080))
        assertNull(pageClientY(viewportY = 900, pageTop = 600, cssViewportWidth = 360.0, viewWidth = 0))
    }

    @Test
    fun `a locator resolves to its range, else to its progression`() {
        assertEquals(312.5, resolvedLocalY(312.5, 0.9, 1000))
        assertEquals(250.0, resolvedLocalY(null, 0.25, 1000))
        assertEquals(0.0, resolvedLocalY(null, 0.0, 1000))
        assertEquals(0.0, resolvedLocalY(0.0, null, 1000))
        assertNull(resolvedLocalY(null, null, 1000))
    }

    @Test
    fun `a focus with no hover before it is not from a touch`() {
        assertFalse(focusFromTouch(10_000, null))
    }

    @Test
    fun `a focus up to the touch window after the last hover is from the touch`() {
        assertTrue(focusFromTouch(10_000, 10_000))
        assertTrue(focusFromTouch(10_000, 10_000 - 85))
        assertTrue(focusFromTouch(10_000, 10_000 - TOUCH_FOCUS_WINDOW_MS))
        assertFalse(focusFromTouch(10_000, 10_000 - TOUCH_FOCUS_WINDOW_MS - 1))
        assertEquals(500L, TOUCH_FOCUS_WINDOW_MS)
    }

    @Test
    fun `a hover stamped after the focus does not make it a touch`() {
        assertFalse(focusFromTouch(10_000, 10_001))
    }
}
