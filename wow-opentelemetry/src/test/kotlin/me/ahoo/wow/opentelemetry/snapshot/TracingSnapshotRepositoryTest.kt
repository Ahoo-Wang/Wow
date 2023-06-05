package me.ahoo.wow.opentelemetry.snapshot

import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotRepositorySpec

class TracingSnapshotRepositoryTest : SnapshotRepositorySpec() {
    override fun createSnapshotRepository(): SnapshotRepository {
        return InMemorySnapshotRepository().tracing()
    }
}
