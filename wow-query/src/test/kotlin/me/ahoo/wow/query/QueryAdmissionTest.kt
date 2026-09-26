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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.util.context.Context
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.NullNode
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

class QueryAdmissionTest {
    private val epoch = scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS))
    private val schema = boundSchemaFixture(
        objectFixture(
            "timeoutAt" to epoch,
            "note" to scalarFixture(),
            "items" to arrayFixture(objectFixture("at" to epoch)),
        ),
    )

    @Test
    fun `admission hands backends a normalized query`() {
        val timeoutAt = QueryField("timeoutAt")
        val admitted = QueryAdmission.Trusted.count(
            AndFilter(
                listOf(
                    AndFilter(listOf(ExistsFilter(timeoutAt), EqualFilter(QueryField("note"), NullNode.instance))),
                    BeforeNowFilter(timeoutAt),
                ),
            ),
            schema,
        )
        val operands = (admitted.query as AndFilter).operands
        operands.take(2).assert().containsExactly(ExistsFilter(timeoutAt), IsNullFilter(QueryField("note")))
        (operands[2] as LessThanFilter).field.assert().isEqualTo(timeoutAt)
        admitted.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
    }

    @Test
    fun `every filter of one aggregation resolves against the same moment`() {
        val at = QueryField("at")
        val admitted = QueryAdmission.Trusted.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("items"), AfterNowFilter(at))),
                metrics = listOf(AggregationMetric.Count("due", BeforeNowFilter(at))),
            ),
            schema,
        ).query
        val element = admitted.elements.single().filter as GreaterThanFilter
        val metric = admitted.metrics.single().filter as LessThanFilter
        element.value.assert().isEqualTo(metric.value)
    }

    private val snapshot = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "tenantId" to scalarFixture(),
            "ownerId" to scalarFixture(),
            "deleted" to scalarFixture(QueryValueType.BOOLEAN),
            "note" to scalarFixture(),
        ),
    )
    private val note = EqualFilter(QueryField("note"), NullNode.instance)
    private val namedAggregate = MaterializedNamedAggregate("admission", "order")

    @Test
    fun `governed admission runs filters, then scope and policies, then the default scope, then the last steps`() {
        val seen = mutableListOf<String>()
        val filter = object : QueryFilter {
            override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> {
                seen += "filter:${(context.query as IListQuery).filter}"
                return Mono.just(context.query)
            }
        }
        val policy = QueryPolicy { _, context ->
            seen += "policy:${(context.query as IListQuery).filter}"
            Mono.just(OwnerIdFilter("o1"))
        }
        val admitted = QueryAdmission(namedAggregate, listOf(filter), listOf(policy))
            .admit(QueryOperation.LIST, ListQuery(note, limit = 10) as IListQuery, Mono.just(snapshot))
            .contextWrite { it.withQueryScope(TenantIdFilter("t1")).withQueryEntry(QueryEntry.HTTP) }
            .block()!!
        seen.assert().containsExactly(
            "filter:$note",
            "policy:${AndFilter(listOf(note, TenantIdFilter("t1")))}",
        )
        (admitted.query.filter as AndFilter).operands.assert().containsExactly(
            IsNullFilter(QueryField("note")),
            TenantIdFilter("t1"),
            OwnerIdFilter("o1"),
            DeletionFilter(DeletionState.ACTIVE),
        )
        admitted.entry.assert().isEqualTo(QueryEntry.HTTP)
    }

    @Test
    fun `a policy's deletion scope replaces the snapshot default`() {
        val policy = QueryPolicy { _, _ -> Mono.just(DeletionFilter(DeletionState.DELETED)) }
        QueryAdmission(namedAggregate, policies = listOf(policy))
            .admit(QueryOperation.COUNT, MatchAllFilter as FilterExpression, Mono.just(snapshot))
            .block()!!.query.assert().isEqualTo(DeletionFilter(DeletionState.DELETED))
    }

    @Test
    fun `governed admission checks the entry budget before the schema loads`() {
        var loaded = false
        QueryAdmission(namedAggregate, entryPolicy = QueryEntryPolicy(requireExplicitEntry = true))
            .admit(
                QueryOperation.COUNT,
                MatchAllFilter as FilterExpression,
                Mono.fromCallable {
                    loaded = true
                    snapshot
                }
            )
            .test()
            .expectErrorMatches { it is QueryRequestException && it.code == QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED }
            .verify()
        loaded.assert().isFalse()
        QueryAdmission(namedAggregate)
            .admit(QueryOperation.COUNT, MatchAllFilter as FilterExpression, Mono.empty())
            .test()
            .expectErrorMessage("QueryModelSchemaProvider must emit one schema.")
            .verify()
    }

    @Test
    fun `the trusted path is named for what it skips and runs no governance`() {
        val policy = QueryPolicy { _, _ -> Mono.just(OwnerIdFilter("o1")) }
        val query = ListQuery(note, limit = 10)
        val trusted = QueryAdmission.Trusted.list(query, snapshot)
        trusted.query.filter.assert().isEqualTo(IsNullFilter(QueryField("note")))
        trusted.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
        val governed = QueryAdmission(namedAggregate, policies = listOf(policy))
            .admit(QueryOperation.LIST, query as IListQuery, Mono.just(snapshot)).block()!!
        governed.query.filter.assert().isNotEqualTo(trusted.query.filter)
        // Every public entry of a governed admission is a publisher that reads the caller's context; only
        // QueryAdmission.Trusted admits synchronously, without an identity.
        QueryAdmission::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .all { Mono::class.java.isAssignableFrom(it.returnType) }
            .assert().isTrue()
        QueryAdmission.Trusted::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .all { AdmittedQuery::class.java.isAssignableFrom(it.returnType) }
            .assert().isTrue()
    }

    @Test
    fun `a route selection is appended at step 2, hidden from filters and not caller scope`() {
        val seen = mutableListOf<FilterExpression>()
        val replacing = object : QueryFilter {
            override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> {
                seen += (context.query as IListQuery).filter
                return Mono.just(context.query.withFilter(MatchAllFilter))
            }
        }
        val policies = mutableListOf<FilterExpression>()
        val policy = QueryPolicy { _, context ->
            policies += (context.query as IListQuery).filter
            Mono.just(MatchAllFilter)
        }
        val selection = EqualFilter(QueryField("aggregateId"), JsonNodeFactory.instance.stringNode("a1"))
        val admitted = QueryAdmission(namedAggregate, listOf(replacing), listOf(policy))
            .admit(QueryOperation.LIST, ListQuery(note, limit = 10) as IListQuery, Mono.just(snapshot))
            .contextWrite { it.withQueryScope(TenantIdFilter("t1")).withQuerySelection(selection) }
            .block()!!
        seen.assert().containsExactly(note)
        policies.single().assert().isEqualTo(AndFilter(listOf(TenantIdFilter("t1"), selection)))
        (admitted.query.filter as AndFilter).operands.assert().containsExactly(
            TenantIdFilter("t1"),
            selection,
            DeletionFilter(DeletionState.ACTIVE),
        )
        Context.empty().withQuerySelection(selection).also {
            it.queryScope().assert().isEqualTo(MatchAllFilter)
            it.forInProcessQuery().querySelection().assert().isEqualTo(MatchAllFilter)
        }
    }

    @Test
    fun `a route selection counts as a deletion scope only when it states one`() {
        val tracing = DeletionFilter(DeletionState.ALL)
        QueryAdmission(namedAggregate)
            .admit(QueryOperation.COUNT, MatchAllFilter as FilterExpression, Mono.just(snapshot))
            .contextWrite { it.withQuerySelection(tracing) }
            .block()!!.query.assert().isEqualTo(tracing)
    }
}
