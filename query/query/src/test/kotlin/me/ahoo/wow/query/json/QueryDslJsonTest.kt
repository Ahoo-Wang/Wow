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

package me.ahoo.wow.query.json

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.SnapshotProperties
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.api.query.dsl.aggregation
import me.ahoo.wow.api.query.dsl.countQuery
import me.ahoo.wow.api.query.dsl.cursorQuery
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.query.dsl.pagedQuery
import me.ahoo.wow.api.query.dsl.singleQuery
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test

/**
 * A query built with the Kotlin DSL is the query its JSON reads as (the body of the query route without `kind`), and
 * runs to the same answer on the in-memory reference backend through the whole gateway.
 */
class QueryDslJsonTest {
    private enum class Status { PAID, NEW }

    @Test
    fun `a list query`() {
        val dsl = listQuery {
            filter {
                "state.status" isIn listOf("PAID", "NEW")
                "state.amount".between(10, 40)
                not { "state.name".startsWithText("gam", StringComparison.CASE_INSENSITIVE) }
            }
            projection { include("aggregateId", "state.amount") }
            sort { "state.amount".desc() }
            limit(10)
        }
        val json = """
            {"filter":{"op":"AND","operands":[
              {"op":"IN","field":"state.status","values":["PAID","NEW"]},
              {"op":"BETWEEN","field":"state.amount","lowerBound":10,"upperBound":40},
              {"op":"NOR","operands":[
                {"op":"STARTS_WITH","field":"state.name","value":"gam","stringComparison":"CASE_INSENSITIVE"}]}]},
             "projection":{"include":["aggregateId","state.amount"]},
             "sort":[{"field":"state.amount","direction":"DESC"}],
             "limit":10}
        """
        val decoded = decode<ListQuery>(json)
        dsl.assert().isEqualTo(decoded)
        roundTrip(dsl)
        runBlocking {
            val rows = gateway.list(dsl, CALLER).toList()
            rows.assert().isEqualTo(gateway.list(decoded, CALLER).toList())
            rows.map { it.path("aggregateId").stringValue() }.assert().containsExactly("o2", "o1")
        }
    }

    @Test
    fun `a paged query`() {
        val dsl = pagedQuery {
            filter {
                or {
                    "state.status" eq "NEW"
                    SnapshotProperties.OPERATOR__ID eq "user-1"
                }
            }
            sort { SnapshotProperties.VERSION.asc() }
            pagination { size(2) }
            total(TotalMode.EXACT)
        }
        val json = """
            {"filter":{"op":"OR","operands":[
              {"op":"EQ","field":"state.status","value":"NEW"},
              {"op":"EQ","field":"operator.id","value":"user-1"}]},
             "sort":[{"field":"version","direction":"ASC"}],
             "pagination":{"index":1,"size":2},
             "total":"EXACT"}
        """
        val decoded = decode<PagedQuery>(json)
        dsl.assert().isEqualTo(decoded)
        roundTrip(dsl)
        runBlocking {
            val page = gateway.paged(dsl, CALLER)
            page.assert().isEqualTo(gateway.paged(decoded, CALLER))
            page.total.assert().isEqualTo(2)
            page.list.map { it.path("aggregateId").stringValue() }.assert().containsExactly("o1", "o3")
        }
    }

    @Test
    fun `a cursor query over an element match`() {
        val dsl = cursorQuery {
            filter {
                "state.lines".elementMatch {
                    "state.lines.sku" eq "B"
                    "quantity" gte 2
                }
            }
            sort { "state.name".asc() }
            size(1)
        }
        val json = """
            {"filter":{"op":"ELEMENT_MATCH","field":"state.lines","predicate":{"op":"AND","operands":[
              {"op":"EQ","field":"sku","value":"B"},{"op":"GTE","field":"quantity","value":2}]}},
             "sort":[{"field":"state.name","direction":"ASC"}],
             "size":1}
        """
        val decoded = decode<CursorQuery>(json)
        dsl.assert().isEqualTo(decoded)
        roundTrip(dsl)
        runBlocking {
            val first = gateway.cursor(dsl, CALLER)
            first.assert().isEqualTo(gateway.cursor(decoded, CALLER))
            first.list.single().path("aggregateId").stringValue().assert().isEqualTo("o1")
            val next = cursorQuery {
                filter(dsl.filter)
                sort(dsl.sort)
                size(1)
                cursor(first.nextCursor)
            }
            gateway.cursor(next, CALLER).list.single().path("aggregateId").stringValue().assert().isEqualTo("o3")
        }
    }

    @Test
    fun `a single query, a runtime value matching as the JSON it writes as`() {
        val dsl = singleQuery {
            filter { "state.status" eq Status.PAID }
            sort { "state.amount".desc() }
        }
        val json = """
            {"filter":{"op":"EQ","field":"state.status","value":"PAID"},
             "sort":[{"field":"state.amount","direction":"DESC"}]}
        """
        val decoded = decode<SingleQuery>(json)
        dsl.withFilter(decoded.filter).assert().isEqualTo(decoded)
        runBlocking {
            val record = gateway.single(dsl, CALLER)
            record.assert().isEqualTo(gateway.single(decoded, CALLER))
            record!!.path("aggregateId").stringValue().assert().isEqualTo("o2")
        }
    }

    @Test
    fun `a count query`() {
        val dsl = countQuery {
            filter { "state.amount" gt 15 }
            limit(1)
        }
        val decoded = decode<CountQuery>("""{"filter":{"op":"GT","field":"state.amount","value":15},"limit":1}""")
        dsl.assert().isEqualTo(decoded)
        roundTrip(dsl)
        runBlocking {
            val count = gateway.count(dsl, CALLER)
            count.assert().isEqualTo(gateway.count(decoded, CALLER))
            count.exact.assert().isFalse()
        }
    }

    @Test
    fun `an aggregate query`() {
        val dsl = aggregation {
            filter { "state.status" ne "CANCELLED" }
            terms("state.status", "status")
            count("count")
            sum("state.amount", "amount")
            sort { "count".desc() }
        }
        val json = """
            {"filter":{"op":"NE","field":"state.status","value":"CANCELLED"},
             "groupBy":[{"type":"TERMS","field":"state.status","alias":"status"}],
             "metrics":[{"type":"COUNT","alias":"count"},
                        {"type":"NUMERIC","function":"SUM","expression":{"type":"FIELD","field":"state.amount"},"alias":"amount"}],
             "sort":[{"field":"count","direction":"DESC"}]}
        """
        val decoded = decode<AggregationQuery>(json)
        dsl.assert().isEqualTo(decoded)
        roundTrip(dsl)
        runBlocking {
            val rows = gateway.aggregate(dsl, CALLER).toList()
            rows.assert().isEqualTo(gateway.aggregate(decoded, CALLER).toList())
            rows.map { it.toString() }.assert().containsExactly(
                """{"status":"PAID","count":2,"amount":30.5}""",
                """{"status":"NEW","count":1,"amount":30.0}""",
            )
        }
    }

    private inline fun <reified Q : Any> decode(json: String): Q = QueryJson.mapper.readValue(json, Q::class.java)

    private inline fun <reified Q : Any> roundTrip(query: Q) {
        decode<Q>(QueryJson.mapper.writeValueAsString(query)).assert().isEqualTo(query)
    }

    private companion object {
        val TYPE = AggregateType("dsl", "order")
        val CALLER: QueryCaller = QueryCaller.IN_PROCESS

        val gateway: DefaultQueryGateway by lazy {
            val storage = InMemoryQueryStorage()
            orders().forEach(storage::save)
            val binding = storage.binding(TYPE)
            val context = QuerySchemaContext(TYPE, QueryModel.SNAPSHOT)
            val provider = DefaultQueryModelSchemaProvider(
                context,
                listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, QueryBackendSpec.DECLARATION)))),
                binding.storage,
            )
            DefaultQueryGateway(TYPE, binding.backend, provider)
        }

        fun orders(): List<SnapshotRecord> = listOf(
            order(
                "o1",
                1,
                """{"name":"Alpha","status":"PAID","amount":10.5,"lines":[{"sku":"B","quantity":2}]}""",
                "user-1"
            ),
            order("o2", 2, """{"name":"Beta","status":"PAID","amount":20,"lines":[{"sku":"A","quantity":1}]}"""),
            order("o3", 3, """{"name":"Gamma","status":"NEW","amount":30,"lines":[{"sku":"B","quantity":3}]}"""),
            order("o4", 4, """{"name":"Delta","status":"CANCELLED","amount":40,"lines":[]}"""),
        )

        fun order(id: String, version: Int, state: String, operator: String? = null) = SnapshotRecord(
            ref = TYPE.ref(QueryBackendSpec.TENANT, id),
            version = version,
            schemaFingerprint = "dsl",
            state = state,
            scope = Scope.NONE,
            firstEventTime = 1,
            lastEventTime = version.toLong(),
            operator = operator?.let { Operator(it, QueryBackendSpec.TENANT, false) },
        )
    }
}
