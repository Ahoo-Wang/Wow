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
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.webflux.route.acceptsEventStream
import me.ahoo.wow.webflux.route.identity.RouteIdentity
import me.ahoo.wow.webflux.route.identity.RouteIdentityBinding
import org.springframework.web.reactive.function.server.ServerRequest
import java.time.Duration
import java.util.*

private const val IDENTITY_DEPRECATION =
    "Scheduled for removal in 10.0.0. Use CommandBuilderExtractor (DefaultCommandBuilderExtractor) for commands " +
        "or QueryRequestScope (DefaultQueryRequestScope) for queries: they read each identity fact where the route " +
        "states it, header aliases included."

/**
 * The tenant the request states for [aggregateMetadata]: its static tenant, else the `{tenantId}` path variable when
 * the route declares it (blank → 400), else `Command-Tenant-Id`. Since 9.3.0 a header contradicting the path tenant
 * is rejected (400).
 */
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getTenantId(aggregateMetadata: AggregateMetadata<*, *>): String? =
    RouteIdentity.of(this).binding(aggregateMetadata).tenantId(this)

/**
 * The owner the request states: the `{ownerId}` path variable when the route declares it (blank → 400), else
 * `Command-Owner-Id`.
 */
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getOwnerId(): String? = RouteIdentity.of(this).bindingOf(OwnerPolicy.NEVER).ownerId(this)

/** The `Wow-Space-Id` header, whatever the aggregate. */
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getSpaceId(): SpaceId? = RouteIdentity.of(this).bindingOf(OwnerPolicy.NEVER).spaceIdHeader(this)

/**
 * The space this request states for the aggregate of [aggregateRouteMetadata]: the `Wow-Space-Id` header when the
 * aggregate is [spaced][AggregateRouteMetadata.spaced], otherwise `null` whatever the request sends.
 */
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getSpaceId(aggregateRouteMetadata: AggregateRouteMetadata<*>): SpaceId? =
    RouteIdentityBinding.of(RouteIdentity.of(this).pathVariables, aggregateRouteMetadata).spaceId(this)

@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getTenantIdOrDefault(aggregateMetadata: AggregateMetadata<*, *>): String {
    return RouteIdentity.of(this).binding(aggregateMetadata).tenantId(this) ?: TenantId.DEFAULT_TENANT_ID
}

/** The `{id}` path variable when the route declares it (blank → 400), else `Command-Aggregate-Id`. */
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getAggregateId(): String? = RouteIdentity.of(this).bindingOf(OwnerPolicy.NEVER).aggregateId(this)

/** As in 9.2: for [OwnerPolicy.AGGREGATE_ID], [ownerId] if given, else [getAggregateId]. */
@Suppress("DEPRECATION")
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getAggregateId(owner: OwnerPolicy, ownerId: String?): String? {
    if (owner == OwnerPolicy.AGGREGATE_ID) {
        return ownerId ?: getAggregateId()
    }
    return getAggregateId()
}

/** As in 9.2: for [OwnerPolicy.AGGREGATE_ID], [getOwnerId], else [getAggregateId]. */
@Suppress("DEPRECATION")
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getAggregateId(owner: OwnerPolicy): String? = getAggregateId(owner, getOwnerId())

// compat(wow<9.3): the AggregateRoute.Owner overloads, for code compiled against 9.2.
@Suppress("DEPRECATION")
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getAggregateId(owner: AggregateRoute.Owner, ownerId: String?): String? =
    getAggregateId(OwnerPolicy.valueOf(owner.name), ownerId)

@Suppress("DEPRECATION")
@Deprecated(IDENTITY_DEPRECATION)
fun ServerRequest.getAggregateId(owner: AggregateRoute.Owner): String? =
    getAggregateId(OwnerPolicy.valueOf(owner.name))

private fun RouteIdentity.bindingOf(owner: OwnerPolicy): RouteIdentityBinding =
    RouteIdentityBinding.of(pathVariables, staticTenantId = null, ownerPolicy = owner, spaced = true)

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
