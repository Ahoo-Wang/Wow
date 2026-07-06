package me.ahoo.wow.opentelemetry.snapshot

import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotStoreSpec

class TracingSnapshotStoreTest : SnapshotStoreSpec() {
    override fun createSnapshotStore(): SnapshotStore {
        return InMemorySnapshotStore().tracing()
    }
}
