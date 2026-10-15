/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Layout

class ContinuousRouteTest {

    @Test
    fun `the default configuration keeps the pager`() {
        assertFalse(
            usesContinuousSurface(continuousScroll = false, scroll = true, verticalText = false, layout = Layout.REFLOWABLE)
        )
    }

    @Test
    fun `reflowable horizontal text in scroll mode uses the surface`() {
        assertTrue(
            usesContinuousSurface(continuousScroll = true, scroll = true, verticalText = false, layout = Layout.REFLOWABLE)
        )
    }

    @Test
    fun `a publication without a layout uses the surface`() {
        assertTrue(
            usesContinuousSurface(continuousScroll = true, scroll = true, verticalText = false, layout = null)
        )
    }

    @Test
    fun `paginated mode keeps the pager`() {
        assertFalse(
            usesContinuousSurface(continuousScroll = true, scroll = false, verticalText = false, layout = Layout.REFLOWABLE)
        )
    }

    @Test
    fun `vertical text keeps the pager`() {
        assertFalse(
            usesContinuousSurface(continuousScroll = true, scroll = true, verticalText = true, layout = Layout.REFLOWABLE)
        )
    }

    @Test
    fun `fixed layout keeps the pager`() {
        assertFalse(
            usesContinuousSurface(continuousScroll = true, scroll = true, verticalText = false, layout = Layout.FIXED)
        )
    }
}
