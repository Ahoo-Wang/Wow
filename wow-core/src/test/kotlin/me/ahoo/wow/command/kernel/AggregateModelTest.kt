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

package me.ahoo.wow.command.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.abac.DefaultResourceTagsApplied
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.api.event.DefaultAggregateRecovered
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.annotation.registerFirst
import me.ahoo.wow.modeling.command.BaseAndMarkedCommand
import me.ahoo.wow.modeling.command.BaseCommand
import me.ahoo.wow.modeling.command.CommandAggregate
import me.ahoo.wow.modeling.command.DoublyMarkedCommand
import me.ahoo.wow.modeling.command.FirstMarker
import me.ahoo.wow.modeling.command.KernelCharacterizationAggregate
import me.ahoo.wow.modeling.command.KernelEvent
import me.ahoo.wow.modeling.command.MarkerCommand
import me.ahoo.wow.modeling.command.ReturnSingle
import me.ahoo.wow.modeling.command.SecondMarker
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.SubCommand
import me.ahoo.wow.modeling.command.UnhandledCommand
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class AggregateModelTest {
    private val metadata = aggregateMetadata<KernelCharacterizationAggregate, KernelCharacterizationAggregate>()

    private fun commandAggregate(): CommandAggregate<KernelCharacterizationAggregate, KernelCharacterizationAggregate> {
        val state = ConstructorStateAggregateFactory.create(
            metadata.state,
            ReturnSingle("id").toCommandMessage(aggregateId = "model-1").aggregateId,
        )
        return SimpleCommandAggregateFactory(InMemoryEventStore()).create(metadata, state)
    }

    @Test
    fun `an aggregate type compiles once and its entries are shared by every command`() {
        val model = metadata.model

        aggregateMetadata<KernelCharacterizationAggregate, KernelCharacterizationAggregate>().model
            .assert().isSameAs(model)
        model.commandEntry(ReturnSingle::class.java).assert().isNotNull()
            .isSameAs(model.commandEntry(ReturnSingle::class.java))
        model.sourcingTable.assert().isSameAs(metadata.state.sourcingTable)
    }

    @Test
    fun `a supertype handler is resolved once per command type, the superclass first`() {
        val model = metadata.model

        val sub = model.commandEntry(SubCommand::class.java)
        sub.assert().isNotNull().isSameAs(model.commandEntry(SubCommand::class.java))
        sub!!.function.assert().isEqualTo(model.commandEntry(BaseCommand::class.java)!!.function)
        model.commandEntry(MarkerCommand::class.java).assert().isNotNull()
        model.commandEntry(UnhandledCommand::class.java).assert().isNull()
        model.commandEntry(UnhandledCommand::class.java).assert().isNull()
    }

    @Test
    fun `several handled supertypes at the nearest distance are ambiguous and the first one wins`() {
        val model = metadata.model

        model.supertypeCandidates(DoublyMarkedCommand::class.java)
            .assert().containsExactly(FirstMarker::class.java, SecondMarker::class.java)
        model.supertypeCandidates(BaseAndMarkedCommand::class.java)
            .assert().containsExactly(BaseCommand::class.java, MarkerCommand::class.java)
        model.supertypeCandidates(SubCommand::class.java).assert().containsExactly(BaseCommand::class.java)
        model.supertypeCandidates(UnhandledCommand::class.java).assert().isEmpty()
        model.commandEntry(DoublyMarkedCommand::class.java)!!.function
            .assert().isEqualTo(model.commandEntry(FirstMarker::class.java)!!.function)
    }

    @Test
    fun `an entry records the handler result and the event stream on the exchange`() {
        val aggregate = commandAggregate()
        val exchange = SimpleServerCommandExchange(ReturnSingle("id").toCommandMessage(aggregateId = "model-1"))

        StepVerifier.create(metadata.model.commandEntry(ReturnSingle::class.java)!!.invoke(aggregate, exchange))
            .assertNext { eventStream ->
                eventStream.version.assert().isEqualTo(1)
                eventStream.first().body.assert().isEqualTo(KernelEvent("single"))
                exchange.getCommandInvokeResult<Any>().assert().isEqualTo(KernelEvent("single"))
                exchange.getEventStream().assert().isSameAs(eventStream)
            }
            .verifyComplete()
    }

    @Test
    fun `built-in commands emit their framework events`() {
        val aggregate = commandAggregate()
        val tags = mapOf("department" to listOf("engineering"))
        val cases = mapOf(
            DefaultDeleteAggregate to DefaultAggregateDeleted,
            DefaultRecoverAggregate to DefaultAggregateRecovered,
            DefaultApplyResourceTags(tags) to DefaultResourceTagsApplied(tags),
        )
        cases.forEach { (command, event) ->
            val message = command.toCommandMessage(
                aggregateId = aggregate.aggregateId.id,
                namedAggregate = aggregate.aggregateId.namedAggregate,
            )
            StepVerifier.create(
                metadata.model.commandEntry(command.javaClass)!!.invoke(aggregate, SimpleServerCommandExchange(message))
            )
                .assertNext { it.first().body.assert().isEqualTo(event) }
                .verifyComplete()
        }
    }

    @Test
    fun `the first function registered for a type keeps it`() {
        val registry = HashMap<Class<*>, FunctionAccessorMetadata<Any, Void>>()
        val first = KernelEventHandlers::onEvent.toFunctionMetadata<Any, Void>()
        val duplicate = KernelEventHandlers::onDuplicate.toFunctionMetadata<Any, Void>()

        registry.registerFirst(first, "sourcing")
        registry.registerFirst(duplicate, "sourcing")

        registry[KernelEvent::class.java].assert().isSameAs(first)
    }
}

@Suppress("UnusedPrivateMember", "UNUSED_PARAMETER")
private class KernelEventHandlers {
    @OnSourcing
    fun onEvent(event: KernelEvent) = Unit

    @OnSourcing
    fun onDuplicate(event: KernelEvent) = Unit
}
