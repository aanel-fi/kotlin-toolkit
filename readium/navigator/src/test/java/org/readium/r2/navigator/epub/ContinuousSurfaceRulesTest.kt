/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        assertEquals(LiveWindow(setOf(0, 1), false), liveWindow(extents, 0, 800, emptySet(), 6))
        assertEquals(LiveWindow(setOf(0, 1, 2), false), liveWindow(extents, 500, 800, emptySet(), 6))
        assertEquals(LiveWindow(setOf(0, 1, 2), false), liveWindow(extents, 1000, 800, emptySet(), 6))
        assertEquals(LiveWindow(setOf(2, 3), false), liveWindow(extents, 3200, 800, emptySet(), 6))
    }

    @Test
    fun `the live window holds the resources a navigation requires`() {
        assertEquals(
            LiveWindow(setOf(0, 1, 3), false),
            liveWindow(List(4) { 1000 }, 0, 800, setOf(3), 6)
        )
    }

    @Test
    fun `a full live window gets no neighbors`() {
        assertEquals(
            LiveWindow(setOf(0, 1, 2, 3, 6, 7), false),
            liveWindow(List(8) { 200 }, 0, 800, setOf(6, 7), 6)
        )
    }

    @Test
    fun `a required set that does not fit is dropped`() {
        assertEquals(
            LiveWindow(setOf(0, 1, 2, 3, 4), true),
            liveWindow(List(8) { 200 }, 0, 800, setOf(5, 6, 7), 6)
        )
    }

    @Test
    fun `a visible range larger than the cap stays whole`() {
        assertEquals(
            LiveWindow((0..7).toSet(), true),
            liveWindow(List(8) { 100 }, 0, 800, emptySet(), 6)
        )
    }

    @Test
    fun `a landing puts the target offset at the alignment line`() {
        val extents = List(10) { 1000 }
        assertEquals(LandingPlan(5300, setOf(5, 6), false), landingPlan(extents, 800, 0, 5, 300.9, 0, 6))
        assertEquals(LandingPlan(4900, setOf(4, 5), false), landingPlan(extents, 800, 0, 5, 300.9, 400, 6))
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
            LandingPlan(9200, setOf(9), false),
            landingPlan(List(10) { 1000 }, 800, 0, 9, 900.0, 0, 6)
        )
    }

    @Test
    fun `a landing that needs more resources than the cap is over capacity`() {
        assertEquals(
            LandingPlan(2000, setOf(10, 11, 12, 13), true),
            landingPlan(List(20) { 200 }, 800, 0, 10, 0.0, 0, 6)
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
}
