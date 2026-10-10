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

package me.ahoo.wow.apiclient.command

import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.rest.WowHeaders

/**
 * The headers of this command request, as one value: the same headers the `@RequestHeader` parameters of
 * [RestCommandGateway.send] send, one entry per header that has a value.
 *
 * The gateway methods that take the headers as one argument (`send(sendUri, headers, command)` of
 * [ReactiveRestCommandGateway] and [SyncRestCommandGateway]) take this, so the header names and values are defined
 * once.
 */
fun CommandRequest.toRequestHeaders(): Map<String, String> = buildMap {
    put(CommandHeaders.COMMAND_TYPE, commandType)
    put(CommandHeaders.WAIT_STAGE, waitPlan.waitStage.name)
    putIfPresent(CommandHeaders.WAIT_CONTEXT, waitPlan.waitContext)
    putIfPresent(CommandHeaders.WAIT_PROCESSOR, waitPlan.waitProcessor)
    putIfPresent(CommandHeaders.WAIT_TIME_OUT, waitPlan.waitTimeout)
    putIfPresent(CommandHeaders.TENANT_ID, tenantId)
    putIfPresent(CommandHeaders.OWNER_ID, ownerId)
    putIfPresent(WowHeaders.SPACE_ID, spaceId)
    putIfPresent(CommandHeaders.AGGREGATE_ID, aggregateId)
    putIfPresent(CommandHeaders.AGGREGATE_VERSION, aggregateVersion)
    putIfPresent(CommandHeaders.REQUEST_ID, requestId)
    putIfPresent(CommandHeaders.LOCAL_FIRST, localFirst)
    putIfPresent(CommandHeaders.COMMAND_AGGREGATE_CONTEXT, context)
    putIfPresent(CommandHeaders.COMMAND_AGGREGATE_NAME, aggregate)
}

private fun MutableMap<String, String>.putIfPresent(name: String, value: Any?) {
    if (value != null) {
        put(name, value.toString())
    }
}
