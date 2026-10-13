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

package me.ahoo.wow.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Sensitivity end to end, from the Manifest's `x-wow-sensitive` through the gateway: every response masks both levels
 * for every caller; a DISPLAY value still compares (unless the policy turns that off), a CONFIDENTIAL one never does,
 * and neither is aggregated or cursor-sorted.
 */
class SensitivityGatewayTest {
    private suspend fun gateway(
        sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
        observer: QueryObserver = QueryObserver.NONE,
    ): Pair<QueryGateway, QueryModelSchema> {
        val storage = InMemoryQueryStorage()
        storage.save(order("o1", "13812345678"))
        storage.save(order("o2", "13900000000"))
        val schema = ManifestFixtures.orderSchema(sensitivity)
        val binding = storage.create(ManifestFixtures.ORDER)
        val gateway = DefaultQueryGateway(
            ManifestFixtures.ORDER,
            binding.backend,
            FixedQueryModelSchemaProvider(schema),
            observer = observer,
        )
        return gateway to schema
    }

    @Test
    fun `responses mask every sensitive value whatever its level and whoever asks`() {
        runTest {
            val (gateway, _) = gateway()
            listOf(QueryCaller.IN_PROCESS, QueryCaller(QueryEntry.HTTP, tenantId = "t")).forEach { caller ->
                val rows = gateway.list(
                    ListQuery(MatchAllFilter, sort = listOf(Sort(ID, Sort.Direction.ASC)), limit = 10),
                    caller
                ).toList()
                rows.first().path("state").run {
                    path("phone").stringValue().assert().isEqualTo("138****5678")
                    path("password").stringValue().assert().isEqualTo("******")
                    path("token").stringValue().assert().isEqualTo("***")
                    path("backupPhone").stringValue().assert().isEqualTo("*****")
                    path("email").stringValue().assert().isEqualTo("[redacted]")
                    path("address").path("street").stringValue().assert().isEqualTo("**********")
                    path("address").path("city").stringValue().assert().isEqualTo("Shanghai")
                    path("buyer").stringValue().assert().isEqualTo("Alice")
                }
            }
            gateway.single(SingleQuery(AggregateIdFilter("o2")), QueryCaller.IN_PROCESS)!!
                .path("state").path("phone").stringValue().assert().isEqualTo("139****0000")
            gateway.paged(PagedQuery(MatchAllFilter), QueryCaller.IN_PROCESS).list
                .map { it.path("state").path("password").stringValue() }.assert().containsOnly("******")
            gateway.cursor(CursorQuery(MatchAllFilter, size = 1), QueryCaller.IN_PROCESS).list.single()
                .path("state").path("phone").stringValue().assert().isEqualTo("138****5678")
        }
    }

    @Test
    fun `a DISPLAY value compares by its raw value, a CONFIDENTIAL one never`() {
        runTest {
            val (gateway, _) = gateway()
            gateway.count(CountQuery(EqualFilter(PHONE, "13812345678")), QueryCaller.IN_PROCESS)
                .count.assert().isEqualTo(1L)
            gateway.list(
                ListQuery(MatchAllFilter, sort = listOf(Sort(PHONE, Sort.Direction.DESC))),
                QueryCaller.IN_PROCESS
            )
                .toList().map { it.path("aggregateId").stringValue() }.assert().containsExactly("o2", "o1")
            rejected(QueryErrorCodes.PROTECTED_COMPARISON) {
                gateway.count(CountQuery(EqualFilter(PASSWORD, "secret")), QueryCaller.IN_PROCESS)
            }
            rejected(QueryErrorCodes.PROTECTED_COMPARISON) {
                gateway.list(
                    ListQuery(MatchAllFilter, sort = listOf(Sort(PASSWORD, Sort.Direction.ASC))),
                    QueryCaller.IN_PROCESS
                ).toList()
            }
            // Nested values and aliases of a protected path are protected too.
            rejected(QueryErrorCodes.PROTECTED_COMPARISON) {
                gateway.count(
                    CountQuery(EqualFilter(QueryField("state.deliverTo.street"), "x")),
                    QueryCaller.IN_PROCESS
                )
            }
        }
    }

    @Test
    fun `the sensitivity policy can stop DISPLAY values from comparing`() {
        runTest {
            val (gateway, schema) = gateway(QuerySensitivityPolicy(displayComparable = false))
            schema.field(PHONE)?.comparable.assert().isFalse()
            rejected(QueryErrorCodes.PROTECTED_COMPARISON) {
                gateway.count(CountQuery(EqualFilter(PHONE, "13812345678")), QueryCaller.IN_PROCESS)
            }
        }
    }

    @Test
    fun `no protected value is aggregated or orders a cursor`() {
        runTest {
            val (gateway, _) = gateway()
            rejected(QueryErrorCodes.PROTECTED_AGGREGATION) {
                gateway.aggregate(
                    AggregationQuery(
                        groupBy = listOf(AggregationGroup.Terms(PHONE, "phone")),
                        metrics = listOf(AggregationMetric.Count("count")),
                    ),
                    QueryCaller.IN_PROCESS,
                ).toList()
            }
            assertThrows<QuerySchemaValidationException> {
                gateway.cursor(
                    CursorQuery(MatchAllFilter, sort = listOf(Sort(PHONE, Sort.Direction.ASC))),
                    QueryCaller.IN_PROCESS
                )
            }
        }
    }

    @Test
    fun `a point read returns the record masked`() {
        runTest {
            val (_, schema) = gateway()
            val record = order("o1", "13812345678").toQueryRecord()
            val admitted = QueryAdmission(ManifestFixtures.ORDER).admitRecord(
                AggregateIdFilter("o1"),
                record,
                schema,
                QueryCaller.IN_PROCESS,
            )
            admitted!!.path("state").path("phone").stringValue().assert().isEqualTo("138****5678")
            schema.maskRecord(order("o1", "13812345678").toQueryRecord())
                .path("state").path("password").stringValue().assert().isEqualTo("******")
        }
    }

    @Test
    fun `the audit names the masked fields a response carries`() {
        runTest {
            val audits = mutableListOf<QueryAudit>()
            val observer = object : QueryObserver {
                override val audits: Boolean = true

                override fun onAudit(audit: QueryAudit) {
                    audits += audit
                }
            }
            val (gateway, _) = gateway(observer = observer)
            gateway.list(ListQuery(MatchAllFilter), QueryCaller.IN_PROCESS).toList()
            gateway.list(
                ListQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("state.address")))),
                QueryCaller.IN_PROCESS,
            ).toList()
            gateway.list(
                ListQuery(MatchAllFilter, projection = Projection(exclude = listOf(QueryField("state")))),
                QueryCaller.IN_PROCESS,
            ).toList()
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            audits[0].maskedFields.assert().contains("state.phone", "state.password", "state.address.street")
            audits[1].maskedFields.assert().containsExactly("state.address.street")
            audits[2].maskedFields.assert().isEmpty()
            audits[3].maskedFields.assert().isEmpty()
            audits[0].toString().assert().contains("maskedFields=")
        }
    }

    private suspend fun rejected(code: String, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        (error as QueryError).bindingErrors.first().code.assert().isEqualTo(code)
    }

    private companion object {
        val ID = QueryField("aggregateId")
        val PHONE = QueryField("state.phone")
        val PASSWORD = QueryField("state.password")

        fun order(id: String, phone: String) = SnapshotRecord(
            ref = ManifestFixtures.ORDER.ref("tenant", id),
            version = 1,
            schemaFingerprint = "test",
            state = """
                {"address":{"city":"Shanghai","street":"Nanjing Rd"},"backupPhone":"12345","buyer":"Alice",
                 "buyerId":"m1","createdAt":1,"currency":"CNY","lines":[{"quantity":1.5,"sku":"A"}],"note":"n",
                 "paid":1.25,"password":"secret","phone":"$phone","placedAt":"2026-01-01T00:00:00Z",
                 "productIds":["p1"],"rate":0.1,"timeout":30,"tip":1,"token":"abc","total":10,
                 "email":"a@b.c","shipping":null}
            """.trimIndent(),
            scope = Scope("owner"),
            firstEventTime = 1,
            lastEventTime = 1,
        )
    }
}
