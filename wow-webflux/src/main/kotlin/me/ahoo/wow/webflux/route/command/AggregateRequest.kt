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

package me.ahoo.wow.webflux.route.command

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.acceptsEventStream
import org.springframework.web.reactive.function.server.ServerRequest
import java.time.Duration
import java.util.*

fun ServerRequest.getTenantId(aggregateMetadata: AggregateMetadata<*, *>): String? {
    aggregateMetadata.staticTenantId.ifNotBlank<String> {
        return it
    }
    return pathOrHeader(MessageRecords.TENANT_ID, CommandComponent.Header.TENANT_ID)
}

fun ServerRequest.getOwnerId(): String? {
    return pathOrHeader(MessageRecords.OWNER_ID, CommandComponent.Header.OWNER_ID)
}

/**
 * The value of [variable] when the matched route declares it, otherwise the [header].
 *
 * A route that declares the variable states the value in its path, and a gateway may authorize on that path segment,
 * so the path is authoritative there: a blank segment (such as `%20`) is rejected with an [IllegalArgumentException]
 * (`IllegalArgument`, 400) instead of falling back to the header, and the header is never consulted. Routes without
 * the variable keep reading the header.
 */
private fun ServerRequest.pathOrHeader(variable: String, header: String): String? {
    val pathVariables = pathVariables()
    if (pathVariables.containsKey(variable)) {
        val value = pathVariables[variable]
        require(!value.isNullOrBlank()) {
            "Path variable [$variable] must not be blank."
        }
        return value
    }
    return headers().firstHeader(header).ifNotBlank { it }
}

fun ServerRequest.getSpaceId(): SpaceId? {
    headers().firstHeader(CommonComponent.Header.SPACE_ID).ifNotBlank<String> {
        return it
    }
    return null
}

/**
 * The space this request states for the aggregate of [aggregateRouteMetadata]: the `Wow-Space-Id` header when the
 * aggregate is [spaced][AggregateRouteMetadata.spaced], otherwise `null` whatever the request sends.
 */
fun ServerRequest.getSpaceId(aggregateRouteMetadata: AggregateRouteMetadata<*>): SpaceId? {
    if (!aggregateRouteMetadata.spaced) {
        return null
    }
    return getSpaceId()
}

fun ServerRequest.getTenantIdOrDefault(aggregateMetadata: AggregateMetadata<*, *>): String {
    return getTenantId(aggregateMetadata) ?: return TenantId.DEFAULT_TENANT_ID
}

fun ServerRequest.getAggregateId(): String? {
    return pathOrHeader(MessageRecords.ID, CommandComponent.Header.AGGREGATE_ID)
}

fun ServerRequest.getAggregateId(owner: AggregateRoute.Owner, ownerId: String?): String? {
    if (owner == AggregateRoute.Owner.AGGREGATE_ID) {
        return ownerId ?: getAggregateId()
    }
    return getAggregateId()
}

fun ServerRequest.getAggregateId(owner: AggregateRoute.Owner): String? {
    if (owner == AggregateRoute.Owner.AGGREGATE_ID) {
        return getOwnerId() ?: getAggregateId()
    }
    return getAggregateId()
}

fun ServerRequest.getLocalFirst(): Boolean? {
    headers().firstHeader(CommandComponent.Header.LOCAL_FIRST).ifNotBlank<String> {
        return it.toBoolean()
    }
    return null
}

fun ServerRequest.isSse(): Boolean {
    return acceptsEventStream()
}

fun ServerRequest.getWaitTimeout(default: Duration = DEFAULT_TIME_OUT): Duration {
    val waitTimeout = headers().firstHeader(CommandComponent.Header.WAIT_TIME_OUT)
        ?: headers().firstHeader(CommandComponent.Header.LEGACY_WAIT_TIME_OUT)
    return waitTimeout?.toLongOrNull()?.let {
        Duration.ofMillis(it)
    } ?: default
}

//region Wait Stage
fun ServerRequest.getWaitStage(): CommandStage {
    return headers().firstHeader(CommandComponent.Header.WAIT_STAGE).ifNotBlank { stage ->
        CommandStage.valueOf(stage.uppercase(Locale.getDefault()))
    } ?: CommandStage.PROCESSED
}

fun ServerRequest.getWaitContext(): String {
    return headers().firstHeader(CommandComponent.Header.WAIT_CONTEXT).orEmpty()
}

fun ServerRequest.getWaitProcessor(): String {
    return headers().firstHeader(CommandComponent.Header.WAIT_PROCESSOR).orEmpty()
}

fun ServerRequest.getWaitFunction(): String {
    return headers().firstHeader(CommandComponent.Header.WAIT_FUNCTION).orEmpty()
}

//endregion
//region Wait Chain Tail
fun ServerRequest.getWaitTailStage(): CommandStage? {
    return headers().firstHeader(CommandComponent.Header.WAIT_TAIL_STAGE).ifNotBlank { stage ->
        CommandStage.valueOf(stage.uppercase(Locale.getDefault()))
    }
}

fun ServerRequest.getWaitTailContext(): String {
    return headers().firstHeader(CommandComponent.Header.WAIT_TAIL_CONTEXT).orEmpty()
}

fun ServerRequest.getWaitTailProcessor(): String {
    return headers().firstHeader(CommandComponent.Header.WAIT_TAIL_PROCESSOR).orEmpty()
}

fun ServerRequest.getWaitTailFunction(): String {
    return headers().firstHeader(CommandComponent.Header.WAIT_TAIL_FUNCTION).orEmpty()
}
//endregion

fun ServerRequest.extractWaitPlan(commandMessage: CommandMessage<Any>): WaitPlan {
    val stage: CommandStage = getWaitStage()
    val waitContext = getWaitContext().ifBlank {
        commandMessage.contextName
    }
    val waitFunction = NamedFunctionInfoData(
        contextName = waitContext,
        processorName = getWaitProcessor(),
        name = getWaitFunction()
    )
    val waitTailStage = getWaitTailStage()
    if (stage == CommandStage.SAGA_HANDLED && waitTailStage != null) {
        val waitTailFunction = NamedFunctionInfoData(
            contextName = getWaitTailContext().ifBlank {
                commandMessage.contextName
            },
            processorName = getWaitTailProcessor(),
            name = getWaitTailFunction()
        )
        return CommandWait.chain(
            waitCommandId = commandMessage.commandId,
            function = waitFunction,
            tailStage = waitTailStage,
            tailFunction = waitTailFunction
        )
    }

    return CommandWait.stage(
        waitCommandId = commandMessage.commandId,
        stage = stage,
        contextName = waitContext,
        processorName = waitFunction.processorName,
        functionName = waitFunction.name
    )
}
