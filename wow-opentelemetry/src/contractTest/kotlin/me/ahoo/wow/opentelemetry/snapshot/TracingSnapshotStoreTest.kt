package me.ahoo.wow.opentelemetry.snapshot

import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.VersionedSnapshotStore
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.eventsourcing.snapshot.VersionedSnapshotStoreSpec

class TracingSnapshotStoreTest : VersionedSnapshotStoreSpec() {
    override fun createSnapshotStore(): VersionedSnapshotStore {
        return InMemorySnapshotStore().tracing()
    }
}
