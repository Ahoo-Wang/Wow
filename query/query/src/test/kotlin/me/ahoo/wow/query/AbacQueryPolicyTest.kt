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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.AbacQueryPolicy.Companion.toFilterExpression
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.querySchema
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AbacQueryPolicyTest {
    private val enabled = AbacQueryPolicy(AbacQueryOptions(enabled = true))

    @Test
    fun `each policy declares how it restricts the events, pinned unless it says otherwise (D254, D259)`() {
        enabled.events.assert().isEqualTo(EventsMode.PUSHDOWN)
        AbacQueryPolicy().events.assert().isEqualTo(EventsMode.NONE)
        ScopeQueryPolicy().events.assert().isEqualTo(EventsMode.PUSHDOWN)
        QueryPolicy { _, _ -> MatchAllFilter }.events.assert().isEqualTo(EventsMode.PINNED)
    }

    @Test
    fun `empty tags match all`() {
        emptyMap<String, List<String>>().toFilterExpression().assert().isSameAs(MatchAllFilter)
    }

    @Test
    fun `one tag matches its values or a resource without the key`() {
        mapOf("dept" to listOf("eng")).toFilterExpression().assert().isEqualTo(publicOr("dept", "eng"))
    }

    @Test
    fun `several tags must all match`() {
        val filter = mapOf("dept" to listOf("eng"), "role" to listOf("admin")).toFilterExpression()

        filter.assert().isEqualTo(AndFilter(listOf(publicOr("dept", "eng"), publicOr("role", "admin"))))
    }

    @Test
    fun `tag values remain backend neutral`() {
        val long = "x".repeat(9000)
        mapOf("department" to listOf(long)).toFilterExpression().assert().isEqualTo(publicOr("department", long))
    }

    @Test
    fun `a wildcard only requires the key, whether or not missing keys match`() {
        val wildcard = mapOf("dept" to listOf("*"))
        wildcard.toFilterExpression().assert().isEqualTo(ExistsFilter(QueryField("tags.dept")))
        wildcard.toFilterExpression(matchMissingTagKey = false).assert().isEqualTo(wildcard.toFilterExpression())
        mapOf("dept" to listOf("eng", "*")).toFilterExpression().assert()
            .isEqualTo(ExistsFilter(QueryField("tags.dept")))
    }

    @Test
    fun `a tag key equal to the tags path stays relative to it`() {
        val filter = mapOf("tags" to listOf("*")).toFilterExpression()

        (filter as ExistsFilter).field.assert().isEqualTo(QueryField("tags.tags"))
    }

    @Test
    fun `missing tag keys stop matching when matchMissingTagKey is off`() {
        mapOf("dept" to listOf("eng", "pm")).toFilterExpression(matchMissingTagKey = false).assert()
            .isEqualTo(InFilter(QueryField("tags.dept"), listOf("eng", "pm")))
    }

    @Test
    fun `a tag key that is not a query path segment matches nothing`() {
        listOf("region code", "a.b", "1-x").forEach { key ->
            mapOf(key to listOf("east")).toFilterExpression().assert().isSameAs(MatchNoneFilter)
            mapOf(key to listOf("*")).toFilterExpression().assert().isSameAs(MatchNoneFilter)
        }
        mapOf("dept" to listOf("eng"), "a.b" to listOf("x")).toFilterExpression().assert()
            .isEqualTo(AndFilter(listOf(publicOr("dept", "eng"), MatchNoneFilter)))
    }

    @Test
    fun `the tags are the prefixed attributes with values, trimmed and distinct`() {
        enabled.tagsOf(
            mapOf(
                "abac.region" to listOf(" east ", "north", "east", ""),
                "abac. dept " to listOf("eng"),
                "abac.empty" to listOf(" ", ""),
                "abac." to listOf("nameless"),
                "role" to listOf("admin"),
            ),
        ).assert().isEqualTo(mapOf("region" to listOf("east", "north"), "dept" to listOf("eng")))
        AbacQueryPolicy(AbacQueryOptions(enabled = true, attributePrefix = "tag:"))
            .tagsOf(mapOf("tag:region" to listOf("east"), "abac.dept" to listOf("eng")))
            .assert().isEqualTo(mapOf("region" to listOf("east")))
    }

    @Test
    fun `a blank attribute prefix is rejected`() {
        assertThrows<IllegalArgumentException> { AbacQueryOptions(attributePrefix = " ") }
    }

    @Test
    fun `the policy is off by default and restricts nothing`() = runTest {
        AbacQueryOptions.DEFAULT.enabled.assert().isFalse()
        val tagged = caller(QueryEntry.HTTP, "abac.region" to listOf("east"))
        AbacQueryPolicy().evaluate(tagged, context()).assert().isSameAs(MatchAllFilter)
        // Off, even a required tag is not required.
        AbacQueryPolicy(AbacQueryOptions(requirePrincipalTags = true))
            .evaluate(caller(QueryEntry.HTTP), context()).assert().isSameAs(MatchAllFilter)
    }

    @Test
    fun `a caller without tags is unrestricted`() = runTest {
        enabled.evaluate(caller(QueryEntry.HTTP), context()).assert().isSameAs(MatchAllFilter)
        enabled.evaluate(caller(QueryEntry.HTTP, "role" to listOf("admin")), context()).assert()
            .isSameAs(MatchAllFilter)
    }

    @Test
    fun `the caller's tags restrict the snapshot query`() = runTest {
        val caller = caller(QueryEntry.HTTP, "abac.dept" to listOf("eng"), "abac.role" to listOf("admin"))

        enabled.evaluate(caller, context()).assert()
            .isEqualTo(mapOf("dept" to listOf("eng"), "role" to listOf("admin")).toFilterExpression())
    }

    @Test
    fun `the caller's tags resolve independently from the query`() = runTest {
        val query = AggregationQuery(
            filter = ExistsFilter(QueryField("tenantId")),
            elements = listOf(AggregationElement(QueryField("state.items"), ExistsFilter(QueryField("sku")))),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val context = QueryContext(query, TYPE, SNAPSHOT_SCHEMA, QueryType.AGGREGATION, QueryEntry.HTTP)

        enabled.evaluate(caller(QueryEntry.HTTP, "abac.dept" to listOf("eng")), context).assert()
            .isEqualTo(publicOr("dept", "eng"))
        context.query.assert().isSameAs(query)
    }

    @Test
    fun `an events query is restricted by the tags each stream recorded, a stream without any matching nothing`() =
        runTest {
            val events = QueryContext<FilterExpression>(
                MatchAllFilter,
                TYPE,
                gatewaySchema(QueryModel.EVENTS),
                QueryType.LIST,
                QueryEntry.HTTP,
            )
            val recorded = IsNotNullFilter(QueryField("tags"))
            enabled.evaluate(caller(QueryEntry.HTTP, "abac.dept" to listOf("eng")), events).assert()
                .isEqualTo(AndFilter(listOf(recorded, publicOr("dept", "eng"))))
            val two = caller(QueryEntry.HTTP, "abac.dept" to listOf("eng"), "abac.role" to listOf("admin"))
            enabled.evaluate(two, events).assert()
                .isEqualTo(AndFilter(listOf(recorded, publicOr("dept", "eng"), publicOr("role", "admin"))))
            // A caller without tags is not restricted, as on the snapshots.
            enabled.evaluate(caller(QueryEntry.HTTP), events).assert().isSameAs(MatchAllFilter)
        }

    @Test
    fun `only snapshot, event and tag-binding dataset queries are restricted`() = runTest {
        val dataset = QueryContext<FilterExpression>(
            MatchAllFilter,
            TYPE,
            gatewaySchema(QueryModel.DATASET),
            QueryType.LIST,
            QueryEntry.HTTP,
        )
        enabled.evaluate(caller(QueryEntry.HTTP, "abac.dept" to listOf("eng")), dataset).assert()
            .isSameAs(MatchAllFilter)
        AbacQueryPolicy(AbacQueryOptions(enabled = true, requirePrincipalTags = true))
            .evaluate(caller(QueryEntry.HTTP), dataset).assert().isSameAs(MatchAllFilter)
    }

    @Test
    fun `required principal tags reject an HTTP query without tags and trust in-process ones`() = runTest {
        val policy = AbacQueryPolicy(AbacQueryOptions(enabled = true, requirePrincipalTags = true))
        listOf(caller(QueryEntry.HTTP), caller(QueryEntry.HTTP, "abac.dept" to emptyList())).forEach { untagged ->
            val error = runCatching { policy.evaluate(untagged, context(QueryEntry.HTTP)) }.exceptionOrNull()
            error.assert().isInstanceOf(AbacPrincipalTagsRequiredException::class.java)
                .hasMessage("Query requires the principal's ABAC tags.")
            (error as AbacPrincipalTagsRequiredException).errorCode.assert().isEqualTo(QueryErrors.FORBIDDEN)
            error.errorCode.assert().isEqualTo("wow.auth.forbidden")
            error.errorMsg.assert().isEqualTo("Query requires the principal's ABAC tags.")
            policy.evaluate(untagged, context(QueryEntry.IN_PROCESS)).assert().isSameAs(MatchAllFilter)
        }
        policy.evaluate(caller(QueryEntry.HTTP, "abac.dept" to listOf("eng")), context(QueryEntry.HTTP)).assert()
            .isEqualTo(publicOr("dept", "eng"))
    }

    @Test
    fun `strict matching applies to the caller's tags`() = runTest {
        val policy = AbacQueryPolicy(AbacQueryOptions(enabled = true, matchMissingTagKey = false))

        policy.evaluate(caller(QueryEntry.HTTP, "abac.dept" to listOf("eng")), context()).assert()
            .isEqualTo(InFilter(QueryField("tags.dept"), listOf("eng")))
    }

    @Test
    fun `the gateway returns only the snapshots the caller's tags reach`() = runTest {
        val type = AggregateType("abac", "order")
        val storage = InMemoryQueryStorage()
        listOf(
            tagged(type, "east", mapOf("region" to listOf("east"))),
            tagged(type, "west", mapOf("region" to listOf("west"))),
            tagged(type, "both", mapOf("region" to listOf("east", "west"))),
            tagged(type, "public", emptyMap()),
            tagged(type, "other-key", mapOf("dept" to listOf("eng"))),
        ).forEach(storage::save)
        val binding = storage.create(type)
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        val provider = DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, DECLARATION)))),
            binding.storage,
        )
        suspend fun ids(options: AbacQueryOptions, caller: QueryCaller): List<String> =
            DefaultQueryGateway(type, binding.backend, provider, policies = listOf(AbacQueryPolicy(options)))
                .list(ListQuery(MatchAllFilter, sort = listOf(Sort(ID, Sort.Direction.ASC)), limit = 100), caller)
                .toList().map { it.path("aggregateId").stringValue() }
        val on = AbacQueryOptions(enabled = true)
        val east = caller(QueryEntry.HTTP, "abac.region" to listOf("east"))

        ids(AbacQueryOptions(), east).assert().containsExactly("both", "east", "other-key", "public", "west")
        ids(on, east).assert().containsExactly("both", "east", "other-key", "public")
        ids(on.copyWith(matchMissingTagKey = false), east).assert().containsExactly("both", "east")
        ids(on, caller(QueryEntry.HTTP, "abac.region" to listOf("*"))).assert()
            .containsExactly("both", "east", "west")
        ids(on, caller(QueryEntry.HTTP, "abac.region" to listOf("east"), "abac.dept" to listOf("ops"))).assert()
            .containsExactly("both", "east", "public")
        ids(on, caller(QueryEntry.HTTP)).assert().containsExactly("both", "east", "other-key", "public", "west")
        val required = on.copyWith(requirePrincipalTags = true)
        runCatching { ids(required, caller(QueryEntry.HTTP)) }.exceptionOrNull().assert()
            .isInstanceOf(AbacPrincipalTagsRequiredException::class.java)
        ids(required, QueryCaller.IN_PROCESS).assert().containsExactly("both", "east", "other-key", "public", "west")
    }

    private fun tagged(type: AggregateType, id: String, tags: Map<String, List<String>>) = SnapshotRecord(
        ref = type.ref("tenant", id),
        version = 1,
        schemaFingerprint = "abac",
        state = """{"name":"$id"}""",
        firstEventTime = 1,
        lastEventTime = 1,
        tags = tags,
    )

    private fun caller(entry: QueryEntry, vararg attributes: Pair<String, List<String>>) =
        QueryCaller(Caller(attributes = attributes.toMap()), entry = entry)

    private fun context(entry: QueryEntry = QueryEntry.HTTP) =
        QueryContext<FilterExpression>(MatchAllFilter, TYPE, SNAPSHOT_SCHEMA, QueryType.LIST, entry)

    /** The default condition of one tag: the key absent or empty (a public resource), or one of [values]. */
    private fun publicOr(key: String, vararg values: String): FilterExpression {
        val field = QueryField("tags.$key")
        return OrFilter(listOf(NotExistsFilter(field), IsEmptyFilter(field), InFilter(field, values.toList())))
    }

    private companion object {
        val TYPE = AggregateType("ctx", "agg")
        val ID = QueryField("aggregateId")
        val SNAPSHOT_SCHEMA: QueryModelSchema = gatewaySchema(QueryModel.SNAPSHOT)
        val DECLARATION = querySchema {
            field("state.name") { types(QueryValueType.STRING) }
        }
    }
}
