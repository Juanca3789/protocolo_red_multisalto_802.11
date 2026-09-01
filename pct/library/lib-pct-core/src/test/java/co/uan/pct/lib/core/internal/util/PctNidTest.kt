package co.uan.pct.lib.core.internal.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PctNidTest {

    @Test
    fun isFull_accepts32Hex() {
        assertTrue(PctNid.isFull("ac6a4d78b48343c688534bbec270eab6"))
    }

    @Test
    fun isFull_rejectsPaddedFake() {
        assertFalse(PctNid.isFull("88ccb6f3000000000000000000000000"))
    }

    @Test
    fun isPrefix_accepts8Hex() {
        assertTrue(PctNid.isPrefix("88ccb6f3"))
    }

    @Test
    fun matches_byPrefixWhenNotFull() {
        assertTrue(PctNid.matches("ac6a4d78b48343c688534bbec270eab6", "ac6a4d78"))
    }
}
