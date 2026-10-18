package cloud.suichuan.app

import cloud.suichuan.app.util.VersionUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilTest {

    @Test
    fun parsesTagsAndVersionNames() {
        assertEquals(listOf(1, 3), VersionUtil.parts("v1.3"))
        assertEquals(listOf(1, 4, 1), VersionUtil.parts("1.4.1-beta"))
        assertEquals(emptyList<Int>(), VersionUtil.parts("latest"))
    }

    @Test
    fun newerTagIsDetected() {
        assertTrue(VersionUtil.isNewer("v1.5", "1.4"))
        assertTrue(VersionUtil.isNewer("v1.10", "1.9"))
        assertTrue(VersionUtil.isNewer("v1.4.1", "1.4"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(VersionUtil.isNewer("v1.4", "1.4"))
        assertFalse(VersionUtil.isNewer("v1.4.0", "1.4"))
        assertFalse(VersionUtil.isNewer("v1.3", "1.4"))
    }

    @Test
    fun unparseableTagIsNeverNewer() {
        assertFalse(VersionUtil.isNewer("nightly", "1.4"))
    }

    @Test
    fun displayAddsPrefixOnce() {
        assertEquals("v1.4", VersionUtil.display("1.4"))
        assertEquals("v1.4", VersionUtil.display("v1.4"))
    }
}
