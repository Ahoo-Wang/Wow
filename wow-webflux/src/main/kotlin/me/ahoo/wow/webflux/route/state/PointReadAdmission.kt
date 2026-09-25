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

package me.ahoo.wow.webflux.route.state

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.query.QueryRequestScope
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Optional admission of state point reads: load by id, version or time, and tracing. They replay events and have no
 * query AST, so the gateway never admits them.
 *
 * Off (the default), they behave as before. On:
 * - the caller's [request scope][QueryRequestScope] is checked in memory against each state's tenant, owner and
 *   space; a state outside it reads as absent (404 for a load, no rows for tracing). A scope node other than the
 *   tenant, owner and space filters fails closed;
 * - tracing emits at most [tracingMaxVersions] versions (`0` disables the cap) and only when the caller's scope
 *   admits every state it emits.
 */
class PointReadAdmission(
    val enabled: Boolean = false,
    private val queryRequestScope: QueryRequestScope = DefaultQueryRequestScope,
    val tracingMaxVersions: Int = DEFAULT_TRACING_MAX_VERSIONS,
) {
    init {
        require(tracingMaxVersions >= 0) { "tracingMaxVersions must be greater than or equal to 0." }
    }

    /** Whether the caller of [request] may read [state]; always `true` when admission is off. */
    fun admits(
        aggregateMetadata: AggregateMetadata<*, *>,
        request: ServerRequest,
        state: ReadOnlyStateAggregate<*>,
    ): Boolean = !enabled || queryRequestScope.resolve(aggregateMetadata, request).filter.admits(state)

    /** Rejects a tracing range of [versions] versions beyond [tracingMaxVersions]. */
    fun requireTracingVersions(versions: Int) {
        if (!enabled || tracingMaxVersions == 0) {
            return
        }
        require(versions <= tracingMaxVersions) {
            "Tracing returns at most [$tracingMaxVersions] versions, [$versions] requested: " +
                "narrow the range with headVersion, tailVersion or limit."
        }
    }

    companion object {
        const val DEFAULT_TRACING_MAX_VERSIONS: Int = 1000
        val DISABLED = PointReadAdmission()
    }
}

private fun FilterExpression.admits(state: ReadOnlyStateAggregate<*>): Boolean = when (this) {
    MatchAllFilter -> true
    is AndFilter -> operands.all { it.admits(state) }
    is TenantIdFilter -> value == state.aggregateId.tenantId
    is OwnerIdFilter -> value == state.ownerId
    is SpaceIdFilter -> value == state.spaceId
    else -> false
}
