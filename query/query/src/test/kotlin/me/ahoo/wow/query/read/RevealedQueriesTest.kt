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

package me.ahoo.wow.query.read

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * The service's own read of `@Sensitive(DISPLAY)` values, asked in so many words (#6509): trusted code's
 * [revealed][me.ahoo.wow.api.read.TrustedQueries.revealed] queries and a handler's, the node's in-process API.
 */
class RevealedQueriesTest {
    data class CustomerState(val name: String, val phone: String, val email: String, val secret: String)

    private val customer = AggregateType("shop", "customer")

    /** The values the custom strategy was asked to mask. */
    private val asked = mutableListOf<String>()
    private val strategies = MaskStrategyResolver.of(
        mapOf(REDACT to MaskStrategy { value -> "[redacted]".also { asked += value.value } }),
    )
    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(
                    AggregateManifest(
                        "customer",
                        state = StateManifest(Manifest.schemaRef("Customer")),
                        queries = listOf(QueryManifest(QueryRoutes.SNAPSHOT)),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "Customer" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "phone" to sensitive("display", mapOf("keepPrefix" to 3, "keepSuffix" to 4)),
                    "email" to sensitive("display", mapOf("strategy" to REDACT)),
                    "secret" to sensitive("confidential", mapOf("keepPrefix" to 0, "keepSuffix" to 0)),
                ),
            ),
        ),
    )
    private val storage = InMemoryQueryStorage().apply {
        save(record("acme", "c-1"))
        save(record("other", "c-2"))
    }

    private fun sensitive(level: String, mask: Map<String, Any>) =
        mapOf("type" to "string", "x-wow-sensitive" to mapOf("level" to level), "x-wow-mask" to mask)

    private fun record(tenant: String, id: String) = SnapshotRecord(
        ref = customer.ref(tenant, id),
        version = 1,
        schemaFingerprint = "f",
        state = STATE,
        scope = Scope("alice"),
        firstEventTime = 1,
        lastEventTime = 1,
    )

    private fun committed(ref: AggregateRef) =
        Snapshot.of<JsonNode>(ref, 1, Scope("alice"), emptyMap(), false, null, 1, 1, WowJson.mapper.readTree(STATE))

    /** The node's API over [storage], its in-process lists at most [maxListSize] rows. */
    private fun api(maxListSize: Int = 1000): InProcessQueryApi {
        val budget = QueryBudget(QueryBudget.IN_PROCESS_LABEL, maxListSize = maxListSize)
        val engine = QueryEngine.Builder(manifest)
            .snapshots(storage) { it == customer }
            .entryPolicy(QueryEntryPolicy(inProcess = budget))
            .maskStrategies(strategies)
            .build()
        return InProcessQueryApi(
            engine,
            ReadModels(listOf(ReadModels.Model(customer, CustomerState::class.java))),
            latest = { committed(it) },
        )
    }

    private suspend fun SnapshotQueries<CustomerState>.all(limit: Int = 10): List<CustomerState> =
        list(ListQuery(MatchAllFilter, sort = listOf(Sort(ID, Sort.Direction.ASC)), limit = limit)).toList()
            .map { it.state }

    @Test
    fun `trusted code that asks should read DISPLAY values raw, CONFIDENTIAL ones masked, the strategy not asked`(): Unit =
        runBlocking {
            val api = api()
            val revealed = api.trusted().revealed()
            revealed.snapshots<CustomerState>().all().first()
                .assert().isEqualTo(CustomerState("Ann", "13812345678", "a@b.c", "******"))
            revealed.snapshots<CustomerState>().state(customer.ref("acme", "c-1"))!!.state
                .assert().isEqualTo(CustomerState("Ann", "13812345678", "a@b.c", "******"))
            asked.assert().isEmpty()
            revealed.revealed().assert().isSameAs(revealed)
            // Trusted code that does not ask, and a caller, read every sensitive value masked.
            api.trusted().snapshots<CustomerState>().all().first()
                .assert().isEqualTo(CustomerState("Ann", "138****5678", "[redacted]", "******"))
            api.of(Caller.of(tenantId = "acme")).snapshots<CustomerState>().all().single()
                .assert().isEqualTo(CustomerState("Ann", "138****5678", "[redacted]", "******"))
            asked.assert().contains("a@b.c")
        }

    @Test
    fun `revealing and unbounded queries should each keep the other`(): Unit = runBlocking {
        val api = api(maxListSize = 1)
        val raw = CustomerState("Ann", "13812345678", "a@b.c", "******")
        // The in-process budget refuses a list of two; the unbounded queries take it.
        runCatching { api.trusted().revealed().snapshots<CustomerState>().all(limit = 2) }.isFailure.assert().isTrue()
        api.trusted().revealed().unbounded().snapshots<CustomerState>().all(limit = 2).assert().containsOnly(raw)
        api.trusted().unbounded().revealed().snapshots<CustomerState>().all(limit = 2).assert().containsOnly(raw)
        api.trusted().unbounded().revealed().unbounded().snapshots<CustomerState>().all(limit = 2)
            .assert().containsOnly(raw)
    }

    @Test
    fun `a handler's revealing queries should keep its tenant, and reveal what they lead to`(): Unit = runBlocking {
        val api = api()
        val raw = CustomerState("Ann", "13812345678", "a@b.c", "******")
        val revealed = api.tenant("acme").revealed()
        revealed.tenantId.assert().isEqualTo("acme")
        revealed.snapshots<CustomerState>().all().assert().containsExactly(raw)
        revealed.snapshots<CustomerState>().state("c-1")!!.state.assert().isEqualTo(raw)
        revealed.revealed().assert().isSameAs(revealed)
        revealed.trusted().snapshots<CustomerState>().all().assert().containsExactly(raw, raw)
        api.tenant("acme").snapshots<CustomerState>().all().single().phone.assert().isEqualTo("138****5678")
        api.tenant("acme").trusted().snapshots<CustomerState>().all().first().phone.assert().isEqualTo("138****5678")
    }

    private companion object {
        const val REDACT = "redact"
        val ID = QueryField("aggregateId")
        const val STATE = """{"name":"Ann","phone":"13812345678","email":"a@b.c","secret":"s3cr3t"}"""
    }
}
