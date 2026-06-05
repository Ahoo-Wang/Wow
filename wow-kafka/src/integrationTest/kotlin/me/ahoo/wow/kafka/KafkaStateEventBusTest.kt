package me.ahoo.wow.kafka

import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.tck.container.KafkaTestFixture
import me.ahoo.wow.tck.eventsourcing.state.StateEventBusSpec
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

class KafkaStateEventBusTest : StateEventBusSpec() {
    @JvmField
    @RegisterExtension
    val kafka = KafkaTestFixture()

    override fun createMessageBus(): StateEventBus {
        return KafkaStateEventBus(
            senderOptions = kafka.senderOptions(),
            receiverOptions = kafka.receiverOptions(),
        )
    }

    override fun Flux<StateEventExchange<*>>.onReceive(onReady: Sinks.Empty<Void>): Flux<StateEventExchange<*>> {
        return contextWrite {
            it.writeReceiverOptionsCustomizer { receiverOptions ->
                receiverOptions.addAssignListener {
                    it.forEach { receiverPartition ->
                        receiverPartition.seekToEnd()
                    }
                    onReady.tryEmitEmpty()
                }
            }
        }
    }
}
