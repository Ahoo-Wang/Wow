/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.modeling.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.annotation.CmdAfter
import me.ahoo.wow.modeling.annotation.CmdCreated
import me.ahoo.wow.modeling.annotation.CmdUpdated
import me.ahoo.wow.modeling.annotation.CreateCmd
import me.ahoo.wow.modeling.annotation.FirstAfter
import me.ahoo.wow.modeling.annotation.LastAfter
import me.ahoo.wow.modeling.annotation.MockAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.MockAsyncAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.UpdateCmd
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class SimpleCommandAggregateProcessingTest {

    @Test
    fun `process creates event stream sources state and stores events`() {
        val eventStore = InMemoryEventStore()
        val aggregate = commandAggregate(eventStore = eventStore)
        val command = Create("aggregate-1", "created").toCommandMessage()

        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(command).setServiceProvider(SimpleServiceProvider()))
        )
            .assertNext { eventStream ->
                eventStream.version.assert().isEqualTo(1)
                eventStream.first().body.assert().isEqualTo(StateChanged("aggregate-1", "created"))
            }
            .verifyComplete()

        aggregate.commandRoot.state().assert().isEqualTo("created")
        aggregate.state.version.assert().isEqualTo(1)
        aggregate.commandState.assert().isEqualTo(CommandState.STORED)
        StepVerifier.create(eventStore.load(aggregate.aggregateId))
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `process rejects command expected version mismatch before invoking command function`() {
        val aggregate = commandAggregate()
        val command = ChangeStateGivenExpectedVersion("aggregate-1", "changed", version = 1).toCommandMessage()

        StepVerifier.create(aggregate.process(SimpleServerCommandExchange(command)))
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(CommandExpectVersionConflictException::class.java)
                (error as CommandExpectVersionConflictException).actualVersion.assert().isEqualTo(0)
            }
            .verify()

        aggregate.commandRoot.state().assert().isNull()
        aggregate.state.version.assert().isEqualTo(0)
    }

    @Test
    fun `process rejects non create command for uninitialized aggregate`() {
        val aggregate = commandAggregate()
        val command = ChangeState("aggregate-1", "changed").toCommandMessage()

        StepVerifier.create(aggregate.process(SimpleServerCommandExchange(command)))
            .expectError(NotFoundResourceException::class.java)
            .verify()
    }

    @Test
    fun `process rejects undefined command after aggregate is initialized`() {
        val aggregate = commandAggregate()
        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(Create("aggregate-1", "created").toCommandMessage()))
        )
            .expectNextCount(1)
            .verifyComplete()

        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(UndefinedCommand("aggregate-1").toCommandMessage()))
        )
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(IllegalArgumentException::class.java)
                error.message.assert().contains("Undefined command")
            }
            .verify()
    }

    @Test
    fun `process rejects command when current command state is not stored`() {
        val aggregate = commandAggregate()
        aggregate.commandState = CommandState.SOURCED

        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(Create("aggregate-1", "created").toCommandMessage()))
        )
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(IllegalStateException::class.java)
                error.message.assert().contains("is not stored")
            }
            .verify()
    }

    @Test
    fun `process rejects recovery command when aggregate is not deleted`() {
        val aggregate = commandAggregate()
        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(Create("aggregate-1", "created").toCommandMessage()))
        )
            .expectNextCount(1)
            .verifyComplete()

        StepVerifier.create(
            aggregate.process(
                SimpleServerCommandExchange(
                    DefaultRecoverAggregate.toCommandMessage(
                        aggregateId = "aggregate-1",
                        namedAggregate = aggregate,
                    )
                )
            )
        )
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(IllegalStateException::class.java)
                error.message.assert().contains("is not deleted")
            }
            .verify()
    }

    @Test
    fun `process rejects owner and space mismatches after aggregate is initialized`() {
        val aggregate = commandAggregate()
        val create = Create("aggregate-1", "created").toCommandMessage(
            ownerId = "owner-1",
            spaceId = "space-1",
        )
        StepVerifier.create(aggregate.process(SimpleServerCommandExchange(create)))
            .expectNextCount(1)
            .verifyComplete()

        StepVerifier.create(
            aggregate.process(
                SimpleServerCommandExchange(
                    ChangeState("aggregate-1", "changed").toCommandMessage(ownerId = "owner-2")
                )
            )
        )
            .expectError(IllegalAccessOwnerAggregateException::class.java)
            .verify()

        StepVerifier.create(
            aggregate.process(
                SimpleServerCommandExchange(
                    ChangeState("aggregate-1", "changed").toCommandMessage(spaceId = "space-2")
                )
            )
        )
            .expectError(IllegalAccessSpaceAggregateException::class.java)
            .verify()
    }

    @Test
    fun `process rejects normal commands while state is deleted and allows recovery command`() {
        val aggregate = commandAggregate()
        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(Create("aggregate-1", "created").toCommandMessage()))
        )
            .expectNextCount(1)
            .verifyComplete()
        StepVerifier.create(
            aggregate.process(
                SimpleServerCommandExchange(
                    DefaultDeleteAggregate.toCommandMessage(
                        aggregateId = "aggregate-1",
                        namedAggregate = aggregate,
                    )
                )
            )
        )
            .expectNextCount(1)
            .verifyComplete()

        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(ChangeState("aggregate-1", "changed").toCommandMessage()))
        )
            .expectError(IllegalAccessDeletedAggregateException::class.java)
            .verify()

        StepVerifier.create(
            aggregate.process(
                SimpleServerCommandExchange(
                    DefaultRecoverAggregate.toCommandMessage(
                        aggregateId = "aggregate-1",
                        namedAggregate = aggregate,
                    )
                )
            )
        )
            .assertNext {
                it.version.assert().isEqualTo(3)
            }
            .verifyComplete()
        aggregate.state.deleted.assert().isFalse()
    }

    @Test
    fun `process resolves external services for command functions`() {
        val aggregate = commandAggregate()
        val serviceProvider = SimpleServiceProvider()
        serviceProvider.register(ExternalService())
        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(Create("aggregate-1", "created").toCommandMessage()))
        )
            .expectNextCount(1)
            .verifyComplete()

        StepVerifier.create(
            aggregate.process(
                SimpleServerCommandExchange(ChangeStateDependExternalService("aggregate-1", "other").toCommandMessage())
                    .setServiceProvider(serviceProvider)
            )
        )
            .expectNextCount(1)
            .verifyComplete()

        aggregate.commandRoot.otherState().assert().isEqualTo("other")
        aggregate.state.state.version.assert().isEqualTo(aggregate.state.version)
    }

    @Test
    fun `error function resolves on error path and preserves original error propagation`() {
        ErrorHandlingCommandAggregate.reset()
        val aggregateMetadata = aggregateMetadata<ErrorHandlingCommandAggregate, ErrorHandlingCommandAggregate>()
        val stateRoot = ErrorHandlingCommandAggregate("aggregate-1")
        val stateAggregate = aggregateMetadata.toStateAggregate(stateRoot, version = 0)
        val aggregate = SimpleCommandAggregate(
            state = stateAggregate,
            commandRoot = stateRoot,
            eventStore = InMemoryEventStore(),
            metadata = aggregateMetadata.command,
        )

        StepVerifier.create(
            aggregate.process(SimpleServerCommandExchange(FailingCommand("aggregate-1").toCommandMessage()))
        )
            .expectErrorSatisfies { error ->
                error.message.assert().isEqualTo("boom")
            }
            .verify()

        ErrorHandlingCommandAggregate.handledErrorMessage.assert().isEqualTo("boom")
    }

    @Test
    fun `after command functions append events in configured order`() {
        aggregateVerifier<MockAfterCommandAggregate, MockAfterCommandAggregate>()
            .whenCommand(CreateCmd)
            .expectEventType(CmdCreated::class.java, CmdAfter::class.java, CmdAfter::class.java, CmdAfter::class.java)
            .verify()
            .then()
            .whenCommand(UpdateCmd)
            .expectEventType(CmdUpdated::class.java, CmdAfter::class.java, CmdAfter::class.java)
            .verify()
    }

    @Test
    fun `async after command functions preserve concatenation order`() {
        aggregateVerifier<MockAsyncAfterCommandAggregate, MockAsyncAfterCommandAggregate>()
            .whenCommand(CreateCmd)
            .expectEventType(CmdCreated::class.java, FirstAfter::class.java, LastAfter::class.java)
            .verify()
    }

    @Test
    fun `to string includes state metadata and command state`() {
        val aggregate = commandAggregate()
        val string = aggregate.toString()

        string.assert().contains("SimpleCommandAggregate(state=")
        string.assert().contains("metadata=")
        string.assert().contains("commandState=STORED")
    }

    private fun commandAggregate(
        eventStore: InMemoryEventStore = InMemoryEventStore()
    ): SimpleCommandAggregate<MockCommandAggregate, MockCommandAggregate> {
        val metadata = aggregateMetadata<MockCommandAggregate, MockCommandAggregate>()
        val stateRoot = MockCommandAggregate("aggregate-1")
        val stateAggregate = metadata.toStateAggregate(stateRoot, version = 0)
        return SimpleCommandAggregate(
            state = stateAggregate,
            commandRoot = stateRoot,
            eventStore = eventStore,
            metadata = metadata.command,
        )
    }
}
