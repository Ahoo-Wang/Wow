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

package me.ahoo.wow.modeling.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.modeling.annotation.CreateCmd
import me.ahoo.wow.modeling.annotation.MockAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.MockAggregate
import me.ahoo.wow.modeling.annotation.UpdateCmd
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.command.SimpleCommandAggregate
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import org.junit.jupiter.api.Test

class CommandAggregateMetadataBehaviorTest {

    @Test
    fun `command aggregate metadata equality hash and string are based on aggregate type`() {
        val metadata = aggregateMetadata<MockAggregate, MockAggregate>().command
        val same = aggregateMetadata<MockAggregate, MockAggregate>().command

        metadata.assert().isEqualTo(same)
        metadata.hashCode().assert().isEqualTo(MockAggregate::class.java.hashCode())
        metadata.toString().assert().isEqualTo("CommandAggregateMetadata(aggregateType=${metadata.aggregateType})")
        metadata.equals(Any()).assert().isFalse()
        metadata.processorName.assert().isEqualTo("MockAggregate")
    }

    @Test
    fun `registered commands combine command functions and mounted commands sorted by order`() {
        val metadata = aggregateMetadata<MockAfterCommandAggregate, MockAfterCommandAggregate>().command

        metadata.registeredDeleteAggregate.assert().isFalse()
        metadata.registeredRecoverAggregate.assert().isFalse()
        metadata.registeredApplyResourceTags.assert().isFalse()
        metadata.registeredCommands.assert().contains(CreateCmd::class.java, UpdateCmd::class.java)
    }

    @Test
    fun `command function registry injects default internal command functions when absent`() {
        val aggregateMetadata = aggregateMetadata<MockAfterCommandAggregate, MockAfterCommandAggregate>()
        val commandRoot = MockAfterCommandAggregate("aggregate-1")
        val stateAggregate = aggregateMetadata.toStateAggregate(commandRoot, version = 0)
        val commandAggregate = SimpleCommandAggregate(
            state = stateAggregate,
            commandRoot = commandRoot,
            eventStore = InMemoryEventStore(),
            metadata = aggregateMetadata.command,
        )

        val registry = aggregateMetadata.command.toCommandFunctionRegistry(commandAggregate)

        registry.keys.assert().contains(
            CreateCmd::class.java,
            UpdateCmd::class.java,
            DefaultRecoverAggregate::class.java,
            DefaultDeleteAggregate::class.java,
            DefaultApplyResourceTags::class.java,
        )
    }
}
