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

import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent

/**
 * The headers of this command request, as one value: the same headers the `@RequestHeader` parameters of
 * [RestCommandGateway.send] send, one entry per header that has a value.
 *
 * The gateway methods that take the headers as one argument (`send(sendUri, headers, command)` of
 * [ReactiveRestCommandGateway] and [SyncRestCommandGateway]) take this, so the header names and values are defined
 * once.
 */
fun CommandRequest.toRequestHeaders(): Map<String, String> = buildMap {
    put(CommandComponent.Header.COMMAND_TYPE, commandType)
    put(CommandComponent.Header.WAIT_STAGE, waitPlan.waitStage.name)
    putIfPresent(CommandComponent.Header.WAIT_CONTEXT, waitPlan.waitContext)
    putIfPresent(CommandComponent.Header.WAIT_PROCESSOR, waitPlan.waitProcessor)
    putIfPresent(CommandComponent.Header.WAIT_TIME_OUT, waitPlan.waitTimeout)
    putIfPresent(CommandComponent.Header.TENANT_ID, tenantId)
    putIfPresent(CommandComponent.Header.OWNER_ID, ownerId)
    putIfPresent(CommonComponent.Header.SPACE_ID, spaceId)
    putIfPresent(CommandComponent.Header.AGGREGATE_ID, aggregateId)
    putIfPresent(CommandComponent.Header.AGGREGATE_VERSION, aggregateVersion)
    putIfPresent(CommandComponent.Header.REQUEST_ID, requestId)
    putIfPresent(CommandComponent.Header.LOCAL_FIRST, localFirst)
    putIfPresent(CommandComponent.Header.COMMAND_AGGREGATE_CONTEXT, context)
    putIfPresent(CommandComponent.Header.COMMAND_AGGREGATE_NAME, aggregate)
}

private fun MutableMap<String, String>.putIfPresent(name: String, value: Any?) {
    if (value != null) {
        put(name, value.toString())
    }
}
