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

import com.google.common.base.Preconditions
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.aware.VersionAware
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.annotation.CmdAfter
import me.ahoo.wow.modeling.annotation.CmdCreated
import me.ahoo.wow.modeling.annotation.CmdUpdated
import me.ahoo.wow.modeling.annotation.CreateCmd
import me.ahoo.wow.modeling.annotation.MockAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.UpdateCmd
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class SimpleCommandAggregateTest {
    private val aggregateMetadata = aggregateMetadata<MockCommandAggregate, MockCommandAggregate>()
    private val serviceProvider: ServiceProvider = SimpleServiceProvider()
    private val eventStore: EventStore = InMemoryEventStore()

    @Test
    fun process() {
        val mockCommandAggregate = MockCommandAggregate(generateGlobalId())
        val simpleStateAggregate = aggregateMetadata.toStateAggregate(mockCommandAggregate, 0)
        val commandAggregate = SimpleCommandAggregate(
            state = simpleStateAggregate,
            commandRoot = mockCommandAggregate,
            eventStore = eventStore,
            metadata = aggregateMetadata.command,
        )
        val create = Create(mockCommandAggregate.id(), "create")
        val commandMessage = create.toCommandMessage(generateGlobalId())
        commandAggregate.process(
            SimpleServerCommandExchange(commandMessage).setServiceProvider(serviceProvider)
        ).block()
        mockCommandAggregate.state().assert().isEqualTo(create.state)
        simpleStateAggregate.version.assert().isEqualTo(1)
        simpleStateAggregate.toString().assert().isNotNull()
        eventStore.load(commandAggregate.aggregateId)
            .test()
            .consumeNextWith {
                it.version.assert().isEqualTo(1)
            }.verifyComplete()
    }

    @Test
    fun processGivenExpectedVersion() {
        val mockCommandAggregate = MockCommandAggregate(generateGlobalId())
        val simpleStateAggregate = aggregateMetadata.toStateAggregate(mockCommandAggregate, 0)
        val commandAggregate = SimpleCommandAggregate(
            state = simpleStateAggregate,
            commandRoot = mockCommandAggregate,
            eventStore = eventStore,
            metadata = aggregateMetadata.command,
        )
        val create = Create(mockCommandAggregate.id(), "create")
        val createCommand = create.toCommandMessage(generateGlobalId())
        commandAggregate.process(SimpleServerCommandExchange(createCommand)).block()

        val changeState = ChangeStateGivenExpectedVersion(mockCommandAggregate.id(), "change", 1)
        val commandMessage = changeState.toCommandMessage(generateGlobalId())
        commandAggregate.process(
            SimpleServerCommandExchange(commandMessage).setServiceProvider(serviceProvider)
        ).block()
        mockCommandAggregate.state().assert().isEqualTo(changeState.state)
        simpleStateAggregate.version.assert().isEqualTo(2)
        eventStore.load(commandAggregate.aggregateId)
            .test()
            .expectNextCount(2)
            .verifyComplete()
    }

    @Test
    fun handleGivenExpectedVersionExpectFail() {
        val mockCommandAggregate = MockCommandAggregate(generateGlobalId())
        val simpleStateAggregate = aggregateMetadata.toStateAggregate(mockCommandAggregate, 0)
        val commandAggregate = SimpleCommandAggregate(
            state = simpleStateAggregate,
            commandRoot = mockCommandAggregate,
            eventStore = eventStore,
            metadata = aggregateMetadata.command,
        )
        val changeState = ChangeStateGivenExpectedVersion(mockCommandAggregate.id(), "change", 1)
        val commandMessage = changeState.toCommandMessage(generateGlobalId())

        var thrown = false
        try {
            commandAggregate.process(SimpleServerCommandExchange(commandMessage).setServiceProvider(serviceProvider))
                .block()
        } catch (exception: CommandExpectVersionConflictException) {
            thrown = true
            exception.command.assert().isEqualTo(commandMessage)
            exception.expectVersion.assert().isEqualTo(commandMessage.aggregateVersion)
            exception.actualVersion.assert().isEqualTo(0)
        }
        thrown.assert().isEqualTo(true)
        mockCommandAggregate.state().assert().isNull()
        simpleStateAggregate.version.assert().isEqualTo(0)
        eventStore.load(commandAggregate.aggregateId)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun processGivenOwnerId() {
        val mockCommandAggregate = MockCommandAggregate(generateGlobalId())
        val simpleStateAggregate = aggregateMetadata.toStateAggregate(mockCommandAggregate, 0)
        val commandAggregate = SimpleCommandAggregate(
            state = simpleStateAggregate,
            commandRoot = mockCommandAggregate,
            eventStore = eventStore,
            metadata = aggregateMetadata.command,
        )
        val create = Create(mockCommandAggregate.id(), "create")
        val createCommand = create.toCommandMessage(generateGlobalId(), ownerId = generateGlobalId())
        commandAggregate.process(SimpleServerCommandExchange(createCommand)).block()
        mockCommandAggregate.state().assert().isEqualTo(create.state)
        simpleStateAggregate.version.assert().isEqualTo(1)
        simpleStateAggregate.ownerId.assert().isEqualTo(createCommand.ownerId)

        val changeState = ChangeStateGivenExpectedVersion(mockCommandAggregate.id(), "change", 1)
        val commandMessage = changeState.toCommandMessage(generateGlobalId(), ownerId = generateGlobalId())
        Assertions.assertThrows(IllegalAccessOwnerAggregateException::class.java) {
            commandAggregate.process(
                SimpleServerCommandExchange(commandMessage).setServiceProvider(serviceProvider)
            ).block()
        }
    }

    @Test
    fun handleWithExternalService() {
        serviceProvider.register(ExternalService())
        val mockCommandAggregate = MockCommandAggregate(generateGlobalId())
        val simpleStateAggregate = aggregateMetadata.toStateAggregate(mockCommandAggregate, 0)
        val commandAggregate = SimpleCommandAggregate(
            state = simpleStateAggregate,
            commandRoot = mockCommandAggregate,
            eventStore = eventStore,
            metadata = aggregateMetadata.command,
        )
        val create = Create(mockCommandAggregate.id(), "create")
        val createCommand = create.toCommandMessage(generateGlobalId())
        commandAggregate.process(SimpleServerCommandExchange(createCommand).setServiceProvider(serviceProvider)).block()

        val changeState = ChangeStateDependExternalService(mockCommandAggregate.id(), "change")
        val commandMessage = changeState.toCommandMessage(generateGlobalId())
        commandAggregate.process(
            SimpleServerCommandExchange(commandMessage).setServiceProvider(serviceProvider)
        ).block()

        mockCommandAggregate.otherState().assert().isEqualTo(changeState.otherState)
        simpleStateAggregate.version.assert().isEqualTo(2)
        eventStore.load(commandAggregate.aggregateId)
            .test()
            .expectNextCount(2)
            .verifyComplete()
    }

    @Test
    fun handleWithAfterCommand() {
        aggregateVerifier<MockAfterCommandAggregate, MockAfterCommandAggregate>()
            .whenCommand(CreateCmd)
            .expectEventType(CmdCreated::class.java, CmdAfter::class.java, CmdAfter::class.java, CmdAfter::class.java)
            .verify()
            .then()
            .whenCommand(UpdateCmd)
            .expectEventType(CmdUpdated::class.java, CmdAfter::class.java, CmdAfter::class.java)
            .verify()
    }
}

class MockCommandAggregate(private val id: String) : VersionAware {
    private var state: String? = null
    private var otherState: String? = null
    override var version: Int = 0
    fun id(): String {
        return id
    }

    fun state(): String? {
        return state
    }

    fun otherState(): String? {
        return otherState
    }

    private fun onCommand(create: Create): StateChanged {
        return StateChanged(create.id, create.state)
    }

    private fun onCommand(changeState: CommandMessage<ChangeState>): StateChanged {
        return StateChanged(changeState.id, changeState.body.state)
    }

    private fun onCommand(changeState: ChangeStateGivenExpectedVersion): StateChanged {
        return StateChanged(changeState.id, changeState.state)
    }

    private fun onCommand(
        changeStateDependExternalService: ChangeStateDependExternalService,
        externalService: ExternalService
    ): OtherStateStateChanged {
        Preconditions.checkNotNull(externalService)
        return OtherStateStateChanged(
            changeStateDependExternalService.id,
            changeStateDependExternalService.otherState,
        )
    }

    private fun onSourcing(stateChanged: StateChanged) {
        state = stateChanged.state
    }

    private fun onSourcing(otherStateStateChanged: OtherStateStateChanged) {
        otherState = otherStateStateChanged.otherState
    }
}

@CreateAggregate
class Create(@AggregateId val id: String, val state: String)
class ChangeState(@AggregateId val id: String, val state: String)

class ChangeStateGivenExpectedVersion(
    @AggregateId val id: String,
    val state: String,
    @AggregateVersion val version: Int
)

class ChangeStateDependExternalService(
    @AggregateId val id: String,
    val otherState: String
)

class StateChanged(@AggregateId val id: String, val state: String)

class OtherStateStateChanged(@AggregateId val id: String, val otherState: String)

class ExternalService
