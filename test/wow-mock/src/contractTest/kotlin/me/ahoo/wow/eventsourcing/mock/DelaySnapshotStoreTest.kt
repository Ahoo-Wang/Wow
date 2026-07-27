package me.ahoo.wow.eventsourcing.mock

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotStoreSpec
import org.junit.jupiter.api.Test

class DelaySnapshotStoreTest : SnapshotStoreSpec() {
    override fun createSnapshotStore(): SnapshotStore {
        return DelaySnapshotStore()
    }

    @Test
    fun `should delegate close`() {
        val delegate = CloseCountingSnapshotStore()

        DelaySnapshotStore(delegate = delegate).close()

        delegate.closeCount.assert().isEqualTo(1)
    }

    private class CloseCountingSnapshotStore : SnapshotStore by NoOpSnapshotStore {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
        }
    }
}
