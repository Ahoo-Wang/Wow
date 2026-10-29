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

package me.ahoo.wow.query.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.admission.QueryTargetException
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tools.jackson.databind.JsonNode

/**
 * The engine checks an HTTP caller again, however it was built (review R5.C-1): a `QueryCaller` an edge, a platform
 * service or user code builds directly reaches no further than `QueryTargets.of` would let it, on every model.
 */
class QueryEngineRecheckTest {
    private val order = AggregateType("shop", "order")
    private val ledger = AggregateType("shop", "ledger")
    private val manifest = Manifest(
        contexts = listOf(ContextManifest("shop", aggregates = listOf(aggregate("order"), aggregate("ledger")))),
    )

    private fun aggregate(name: String) = AggregateManifest(
        name,
        state = StateManifest(Manifest.schemaRef(name)),
        queries = listOf(QueryManifest(QueryRoutes.SNAPSHOT), QueryManifest(QueryRoutes.EVENTS)),
    )

    private fun engine(crossTenant: Boolean = false) = QueryEngine.Builder(manifest)
        .snapshots(InMemoryQueryStorage())
        .events { type -> InMemoryEventQueryStorage({ emptyList() }).binding(type) }
        .tenants(TenantPolicy.fixed(mapOf(ledger to "platform")))
        .crossTenant(crossTenant)
        .build()

    /** The models a caller reads through the engine: the snapshot and events queries, and the state reads. */
    enum class Model {
        SNAPSHOT,
        EVENTS,
        STATE,
    }

    private suspend fun QueryEngine.read(model: Model, type: AggregateType, caller: QueryCaller) {
        fun gateway(found: QueryGateway?) = checkNotNull(found)
        when (model) {
            Model.SNAPSHOT -> gateway(snapshots(type)).list(ListQuery(MatchAllFilter, limit = 10), caller).toList()
            Model.EVENTS -> gateway(events(type)).list(ListQuery(MatchAllFilter, limit = 10), caller).toList()
            Model.STATE -> reads.admitted(caller, state(type))
        }
    }

    private fun state(type: AggregateType) = Snapshot.of<JsonNode>(
        ref = type.ref("acme", "x-1"),
        version = 1,
        scope = Scope.NONE,
        deleted = false,
        tags = emptyMap(),
        operator = null,
        firstEventTime = 0,
        lastEventTime = 0,
        state = WowJson.mapper.createObjectNode(),
    )

    private fun refused(code: String, read: suspend () -> Unit) {
        assertThrows<QueryTargetException> { runBlocking { read() } }.errorCode.assert().isEqualTo(code)
    }

    private val alice = Caller(tenantId = "acme", subject = "alice")
    private val operator = Caller(tenantId = "acme", subject = "ops", crossScope = true)

    @ParameterizedTest
    @EnumSource(Model::class)
    fun `a cross-tenant caller built directly is refused while cross-tenant queries are off`(model: Model) {
        val across = QueryCaller(operator, QueryEntry.HTTP, crossTenant = CrossTenant.ALL)
        refused(QueryErrors.FORBIDDEN) { engine().read(model, order, across) }
    }

    @ParameterizedTest
    @EnumSource(Model::class)
    fun `a target named by a caller that may not act across scopes is refused`(model: Model) {
        val named = QueryCaller(alice, QueryEntry.HTTP, target = TargetScope.of("other"))
        refused(QueryErrors.FORBIDDEN) { engine(crossTenant = true).read(model, order, named) }
        val across = QueryCaller(alice, QueryEntry.HTTP, crossTenant = CrossTenant.ALL)
        refused(QueryErrors.FORBIDDEN) { engine(crossTenant = true).read(model, order, across) }
        val nobody = QueryCaller(null, QueryEntry.HTTP, target = TargetScope.of("other"))
        refused(QueryErrors.FORBIDDEN) { engine(crossTenant = true).read(model, order, nobody) }
    }

    @ParameterizedTest
    @EnumSource(Model::class)
    fun `a type whose tenant is fixed is read in it alone`(model: Model) {
        val elsewhere = QueryCaller(operator, QueryEntry.HTTP, crossTenant = CrossTenant(setOf("acme")))
        refused(QueryErrors.MALFORMED) { engine(crossTenant = true).read(model, ledger, elsewhere) }
        val named = QueryCaller(operator, QueryEntry.HTTP, target = TargetScope.of("acme"))
        refused(QueryErrors.MALFORMED) { engine(crossTenant = true).read(model, ledger, named) }
    }

    @ParameterizedTest
    @EnumSource(Model::class)
    fun `what the rules let a caller name, and in-process code, still read`(model: Model): Unit = runBlocking {
        val engine = engine(crossTenant = true)
        engine.read(model, order, engine.targets.of(order, operator, reads = CrossTenant.ALL))
        engine.read(model, ledger, QueryCaller(operator, QueryEntry.HTTP, crossTenant = CrossTenant(setOf("platform"))))
        engine.read(model, order, QueryCaller(operator, QueryEntry.HTTP, target = TargetScope.of("other")))
        engine.read(model, order, QueryCaller.of(alice, QueryEntry.HTTP))
        // In-process code is trusted by its entry.
        engine().read(model, order, QueryCaller(null, QueryEntry.IN_PROCESS, crossTenant = CrossTenant.ALL))
    }

    @Test
    fun `the engine's targets carry its tenants and cross-tenant switch`() {
        engine().crossTenant.assert().isFalse()
        engine(crossTenant = true).crossTenant.assert().isTrue()
        assertThrows<QueryTargetException> { engine().targets.admit(operator, reads = CrossTenant.ALL) }
        engine(crossTenant = true).targets.admit(operator, reads = CrossTenant.ALL)
    }
}
