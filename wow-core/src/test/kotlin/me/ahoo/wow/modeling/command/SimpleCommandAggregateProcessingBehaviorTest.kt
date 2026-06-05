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

class SimpleCommandAggregateProcessingBehaviorTest {

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
    fun `process rejects owner and space mismatches after aggregate is initialized`() {
        val aggregate = commandAggregate()
        val create = Create("aggregate-1", "created").toCommandMessage(
            ownerId = "owner-1",
            spaceId = "space-1",
        )
        aggregate.process(SimpleServerCommandExchange(create)).block()

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
        aggregate.process(SimpleServerCommandExchange(Create("aggregate-1", "created").toCommandMessage())).block()
        aggregate.process(
            SimpleServerCommandExchange(
                DefaultDeleteAggregate.toCommandMessage(
                    aggregateId = "aggregate-1",
                    namedAggregate = aggregate,
                )
            )
        ).block()

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
        aggregate.process(SimpleServerCommandExchange(Create("aggregate-1", "created").toCommandMessage())).block()

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
