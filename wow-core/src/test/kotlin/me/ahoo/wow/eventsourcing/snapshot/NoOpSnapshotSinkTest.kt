package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotSinkSpec

class NoOpSnapshotSinkTest : SnapshotSinkSpec() {
    override fun createSnapshotSink(): SnapshotSink {
        return NoOpSnapshotSink
    }
}
