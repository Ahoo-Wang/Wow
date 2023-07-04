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

package me.ahoo.wow.compiler

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.command.CommandMessage

@me.ahoo.wow.api.annotation.CreateAggregate
class CreateAggregate(@AggregateId val targetId: String, val state: String)

class ChangeAggregate(@AggregateId val targetId: String, val state: String, @AggregateVersion val version: Int? = null)

class ChangeAggregateDependExternalService(
    @AggregateId val targetId: String,
    val state: String
)

data class AggregateCreated(val state: String)
data class AggregateChanged(val state: String)

interface ExternalService

@StaticTenantId
interface CompilerAggregate

@AggregateRoot
class MockCompilerAggregate(val id: String) : CompilerAggregate {
    private var state: String? = null

    fun state(): String? {
        return state
    }

    @OnCommand(returns = [AggregateCreated::class])
    private fun onCommand(create: CreateAggregate): AggregateCreated {
        return AggregateCreated(create.state)
    }

    private fun onCommand(change: CommandMessage<ChangeAggregate>): AggregateChanged {
        return AggregateChanged(change.body.state)
    }

    @Suppress("UnusedParameter")
    @OnCommand
    private fun onChangeAggregateDependExternalService(
        commandBody: ChangeAggregateDependExternalService,
        externalService: ExternalService
    ): AggregateChanged {
        return AggregateChanged(commandBody.state)
    }

    private fun onSourcing(aggregateCreated: AggregateCreated) {
        state = aggregateCreated.state
    }

    private fun onSourcing(changed: AggregateChanged) {
        state = changed.state
    }
}
