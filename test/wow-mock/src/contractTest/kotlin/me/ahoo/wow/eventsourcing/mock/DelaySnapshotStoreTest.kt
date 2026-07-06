package me.ahoo.wow.eventsourcing.mock

import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotStoreSpec

class DelaySnapshotStoreTest : SnapshotStoreSpec() {
    override fun createSnapshotStore(): SnapshotStore {
        return DelaySnapshotStore()
    }
}
