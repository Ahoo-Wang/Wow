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

package me.ahoo.wow.test.spec.modeling.command

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.wow.api.annotation.AggregateId

@me.ahoo.wow.api.annotation.CreateAggregate
class CreateAggregate(@AggregateId val id: String, val state: String)

class ChangeAggregate(@AggregateId val id: String, val state: String)

data class AggregateCreated(val state: String)
data class AggregateChanged(val state: String)

class MockAggregate(@param:JsonProperty("id") val id: String) {
    private var state: String? = null

    fun state(): String? {
        return state
    }

    private fun onCommand(create: CreateAggregate): AggregateCreated {
        return AggregateCreated(create.state)
    }

    private fun onCommand(change: ChangeAggregate): AggregateChanged {
        return AggregateChanged(change.state)
    }

    private fun onSourcing(aggregateCreated: AggregateCreated) {
        state = aggregateCreated.state
    }

    private fun onSourcing(changed: AggregateChanged) {
        state = changed.state
    }
}
