package me.ahoo.wow.kafka

import me.ahoo.wow.eventsourcing.snapshot.SnapshotSink
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotSinkSpec
import org.junit.jupiter.api.BeforeAll

class KafkaSnapshotSinkTest : SnapshotSinkSpec() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun waitLauncher() {
            KafkaLauncher.isRunning
        }
    }

    override fun createSnapshotSink(): SnapshotSink {
        return KafkaSnapshotSink(
            senderOptions = KafkaLauncher.senderOptions
        )
    }
}
