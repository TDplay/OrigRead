package me.ash.reader.ui.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrigReadMessageEntranceRegistryTest {
    @Test fun historyAndRecycledRowsDoNotReplayButNewNodesEnterOnce() {
        val registry = OrigReadMessageEntranceRegistry(1000)
        assertFalse(registry.shouldAnimate("history", 999))
        assertTrue(registry.shouldAnimate("new", 1001))
        assertFalse(registry.shouldAnimate("new", 1001))
        assertFalse(registry.shouldAnimate("history", 999))
        assertTrue(registry.shouldAnimate("another", 1002))
    }
}
