/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeometrySnapshotTest {

    @Test
    fun `the script's report is read`() {
        assertEquals(
            GeometrySnapshot(reasons = setOf("resize", "fonts"), sequence = 4, extentCssPx = 1234.5),
            parseGeometrySnapshot("""{"sequence":4,"reasons":["resize","fonts"],"extentCssPx":1234.5,"other":true}""")
        )
    }

    @Test
    fun `a report that is not the script's is dropped`() {
        listOf(
            "",
            "x",
            "[]",
            "null",
            """{"sequence":4,"reasons":["resize"]}""",
            """{"sequence":4,"extentCssPx":10}""",
            """{"reasons":["resize"],"extentCssPx":10}""",
            """{"sequence":"4a","reasons":["resize"],"extentCssPx":10}""",
            """{"sequence":0,"reasons":["resize"],"extentCssPx":10}""",
            """{"sequence":4,"reasons":"resize","extentCssPx":10}""",
            """{"sequence":4,"reasons":[1,2],"extentCssPx":10}""",
            """{"sequence":4,"reasons":["resize"],"extentCssPx":-1}""",
            """{"sequence":4,"reasons":["resize"],"extentCssPx":"NaN"}""",
            """{"sequence":4,"reasons":["resize"],"extentCssPx":1e12}""",
            """{"sequence":4,"reasons":["a","b","c","d","e","f","g","h","i"],"extentCssPx":10}"""
        ).forEach { assertNull(parseGeometrySnapshot(it), it) }
    }

    @Test
    fun `a report longer than the script's is dropped before it is parsed`() {
        val padding = "x".repeat(MAX_GEOMETRY_SNAPSHOT_LENGTH)
        assertNull(
            parseGeometrySnapshot("""{"sequence":4,"reasons":["resize"],"extentCssPx":10,"pad":"$padding"}""")
        )
    }
}
