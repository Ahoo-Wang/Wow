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

@file:JvmName("SagaContexts")

package me.ahoo.wow.api.subscribe

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.toCommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.schedule.ScheduleRule

/**
 * Calls [command] on the aggregate [id] names, with the fields [block] sets: [SagaContext.call] of [command]'s
 * [toCommandRequest], `context.call(SubmitOrder(items))`.
 */
suspend fun <C : Any> SagaContext.call(
    command: C,
    id: String? = null,
    to: AggregateRef? = null,
    block: CommandRequest.Builder<C>.() -> Unit = {},
): CommandResult = call(command.toCommandRequest(id, to, block))

/**
 * Schedules [command] on the aggregate [id] names under [key] as [rule] says, with the fields [block] sets:
 * [SagaContext.schedule] of [command]'s [toCommandRequest],
 * `context.schedule(DEADLINE, ScheduleRule.after(10.minutes), CancelOrder("No stock in time."), event.ref.id)`.
 */
fun <C : Any> SagaContext.schedule(
    key: String,
    rule: ScheduleRule,
    command: C,
    id: String? = null,
    to: AggregateRef? = null,
    block: CommandRequest.Builder<C>.() -> Unit = {},
) {
    schedule(key, rule, command.toCommandRequest(id, to, block))
}
