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
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.maskRecord
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.withQueryEntry
import me.ahoo.wow.query.withQueryScope
import me.ahoo.wow.serialization.state.StateAggregateRecords
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.query.QueryRequestScope
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional admission of state point reads: load by id, version or time, and tracing. They replay events and have no
 * query AST, so the gateway never admits them.
 *
 * Off (the default), they behave as before. On, each state read is turned into its snapshot-shaped record and
 * admitted by the core ([QueryAdmission.admitRecord]) under an [QueryEntry.HTTP] entry and the caller's
 * [request scope][QueryRequestScope]:
 * - the gateways' [entryPolicy] accepts the entry and, with `require-authenticated-scope` on, rejects a caller whose
 *   authenticated scope does not pin the snapshot model's required scope, as a query route does;
 * - the caller's scope, every [QueryPolicy] restriction and the snapshot model's default scope are evaluated on it in
 *   memory; a state outside them reads as absent (404 for a load, no rows for tracing), and a filter node the
 *   in-memory evaluation does not support fails closed;
 * - the response is masked by the aggregate's snapshot query schema ([snapshotSchema]), and a tracing response also by
 *   its event-stream query schema ([eventStreamSchema]);
 * - tracing emits at most [tracingMaxVersions] versions (`0` disables the cap), and only when every traced state is
 *   admitted.
 *
 * Policies see a [QueryType.SINGLE] query by id; a traced state's query also includes deleted states, since tracing
 * replays history. Policies need the snapshot schema, so a point read with policies fails when [snapshotSchema] is not
 * given or cannot load.
 */
class PointReadAdmission(
    val enabled: Boolean = false,
    private val queryRequestScope: QueryRequestScope = DefaultQueryRequestScope,
    val tracingMaxVersions: Int = DEFAULT_TRACING_MAX_VERSIONS,
    private val policies: List<QueryPolicy> = emptyList(),
    private val snapshotSchema: ((AggregateMetadata<*, *>) -> Mono<QueryModelSchema>)? = null,
    private val eventStreamSchema: ((AggregateMetadata<*, *>) -> Mono<QueryModelSchema>)? = null,
    private val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) {
    private val admissions = ConcurrentHashMap<MaterializedNamedAggregate, QueryAdmission>()

    init {
        require(tracingMaxVersions >= 0) { "tracingMaxVersions must be greater than or equal to 0." }
    }

    /**
     * The admitted, masked record of [state] (a state aggregate or a traced state event), or empty when the caller
     * may not read it.
     */
    fun read(
        aggregateMetadata: AggregateMetadata<*, *>,
        request: ServerRequest,
        state: ReadOnlyStateAggregate<*>,
        tracing: Boolean = false,
    ): Mono<ObjectNode> = Mono.defer {
        val admission = admissions.computeIfAbsent(aggregateMetadata.namedAggregate.materialize()) {
            QueryAdmission(it, policies = policies, entryPolicy = entryPolicy)
        }
        val selection: FilterExpression = IdFilter(state.aggregateId.id).let {
            if (tracing) AndFilter(listOf(it, DeletionFilter(DeletionState.ALL))) else it
        }
        schemaOf(snapshotSchema, aggregateMetadata).flatMap { snapshot ->
            admission.admitRecord(selection, state.toJsonNode(), snapshot.orElse(null)).flatMap { masked ->
                if (!tracing) {
                    return@flatMap Mono.just(masked)
                }
                schemaOf(eventStreamSchema, aggregateMetadata).map { events ->
                    events.map { it.maskRecord(masked) }.orElse(masked)
                }
            }
        }
    }.contextWrite {
        it.withQueryScope(queryRequestScope.resolve(aggregateMetadata, request)).withQueryEntry(QueryEntry.HTTP)
    }

    /** What a load route returns for [state]: its state, or, when enabled, the admitted and masked state JSON. */
    fun state(
        aggregateMetadata: AggregateMetadata<*, *>,
        request: ServerRequest,
        state: ReadOnlyStateAggregate<*>,
    ): Mono<Any> = if (enabled) {
        read(aggregateMetadata, request, state).mapNotNull { it.get(StateAggregateRecords.STATE) }
    } else {
        Mono.just(state.state)
    }

    private fun schemaOf(
        schema: ((AggregateMetadata<*, *>) -> Mono<QueryModelSchema>)?,
        aggregateMetadata: AggregateMetadata<*, *>,
    ): Mono<Optional<QueryModelSchema>> =
        schema?.invoke(aggregateMetadata)?.map { Optional.of(it) } ?: Mono.just(Optional.empty())

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
