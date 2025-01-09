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

package me.ahoo.wow.tck.mock

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.VoidCommand
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregateAware

val MOCK_AGGREGATE_METADATA = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()

@CreateAggregate
data class MockCreateAggregate(val id: String, val data: String)

data class MockChangeAggregate(val id: String, val data: String)

data class MockAggregateCreated(val data: String)
data class MockAggregateChanged(val data: String)

@VoidCommand
data class MockVoidCommand(val data: String)

@AggregateRoot(commands = [MockVoidCommand::class])
class MockCommandAggregate(val state: MockStateAggregate) {

    @OnCommand
    private fun onCommand(create: MockCreateAggregate): MockAggregateCreated {
        return MockAggregateCreated(create.data)
    }

    @Suppress("UnusedParameter")
    private fun onError(create: MockCreateAggregate, throwable: Throwable) {
        throw throwable
    }

    private fun onCommand(change: MockChangeAggregate): MockAggregateChanged {
        return MockAggregateChanged(change.data)
    }
}

data class MockStateAggregate(val id: String) : ReadOnlyStateAggregateAware<MockStateAggregate> {
    var data: String = ""
        private set

    @field:JsonIgnore
    private var readOnlyStateAggregate: ReadOnlyStateAggregate<MockStateAggregate>? = null
    private fun onSourcing(aggregateCreated: MockAggregateCreated) {
        data = aggregateCreated.data
    }

    private fun onSourcing(changed: MockAggregateChanged) {
        data = changed.data
    }

    override fun setReadOnlyStateAggregate(readOnlyStateAggregate: ReadOnlyStateAggregate<MockStateAggregate>) {
        this.readOnlyStateAggregate = readOnlyStateAggregate
    }
}
