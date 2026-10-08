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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class ContinuousResourceScrollViewTest {

    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private fun surface() = ContinuousResourceScrollView(
        context = activity,
        fragments = activity.supportFragmentManager,
        resources = emptyList(),
        expectsNavigation = true,
        onPositionChanged = {}
    ).also { activity.setContentView(it) }

    private val ContinuousResourceScrollView.content: View get() = getChildAt(0)

    @Test
    fun `a first position that does not arrive reveals the surface after the timeout`() {
        val surface = surface()
        assertEquals(View.INVISIBLE, surface.content.visibility)

        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS)

        assertEquals(View.VISIBLE, surface.content.visibility)
    }

    @Test
    fun `a disposed surface does not act on its timeout`() {
        val surface = surface()
        surface.dispose()

        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS)

        assertEquals(View.INVISIBLE, surface.content.visibility)
    }
}
