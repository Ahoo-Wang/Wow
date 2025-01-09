package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.InMemoryMessageBus
import me.ahoo.wow.messaging.withLocalFirst
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.tck.command.CommandBusSpec
import me.ahoo.wow.tck.mock.MockVoidCommand
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration

class LocalFirstCommandBusTest : CommandBusSpec() {
    override fun createMessageBus(): CommandBus {
        return LocalFirstCommandBus(distributedBus = MockDistributedCommandBus())
    }

    @Test
    fun sendNotLocalFirst() {
        verify {
            val onReady = Sinks.empty<Void>()
            val message = createMessage()
            message.header.withLocalFirst(false)
            receive(setOf(namedAggregate))
                .writeReceiverGroup(GlobalIdGenerator.generateAsString())
                .onReceive(onReady)
                .doOnSubscribe {
                    onReady.asMono()
                        .then(send(message))
                        .delaySubscription(Duration.ofMillis(100))
                        .subscribe()
                }
                .test()
                .consumeNextWith {
                    assertThat(it.message.id, equalTo(message.id))
                }
                .thenCancel()
                .verify()
        }
    }

    @Test
    fun sendVoidCommand() {
        verify {
            val onReady = Sinks.empty<Void>()
            val message = MockVoidCommand(generateGlobalId()).toCommandMessage()
            receive(setOf(namedAggregate))
                .writeReceiverGroup(GlobalIdGenerator.generateAsString())
                .onReceive(onReady)
                .doOnSubscribe {
                    onReady.asMono()
                        .then(send(message))
                        .delaySubscription(Duration.ofMillis(10))
                        .subscribe()
                }
                .test()
                .consumeNextWith {
                    assertThat(it.message.id, equalTo(message.id))
                    assertThat(it.message.isVoid, equalTo(true))
                }
                .thenCancel()
                .verify()
        }
    }
}

class MockDistributedCommandBus(
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<CommandMessage<*>> = {
        Sinks.many().multicast().onBackpressureBuffer()
    }
) : DistributedCommandBus, InMemoryMessageBus<CommandMessage<*>, ServerCommandExchange<*>>() {
    override fun CommandMessage<*>.createExchange(): ServerCommandExchange<*> {
        return SimpleServerCommandExchange(this)
    }
}
