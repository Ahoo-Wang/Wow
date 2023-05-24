package me.ahoo.wow.eventsourcing.mock

import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotRepositorySpec

class DelaySnapshotRepositoryTest : SnapshotRepositorySpec() {
    override fun createSnapshotRepository(): SnapshotRepository {
        return DelaySnapshotRepository()
    }
}
