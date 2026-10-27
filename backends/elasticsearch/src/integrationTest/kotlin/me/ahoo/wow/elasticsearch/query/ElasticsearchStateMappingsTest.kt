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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.querySchema
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import java.io.StringReader

/** How each logical value of a state schema maps, and how lists stream past one page. */
class ElasticsearchStateMappingsTest {
    private val type = AggregateType(ElasticsearchFixture.newContext(), "thing")
    private val index = ElasticsearchFixture.names.snapshots(type.context, type.name)
    private val store get() = ElasticsearchFixture.store().snapshots(type.context) as ElasticsearchSnapshotStore

    private suspend fun schema(queries: ElasticsearchSnapshotQueries, declaration: QuerySchemaDeclaration): QueryModelSchema {
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        return DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, declaration)))),
            queries.binding(type).storage,
        ).schema()
    }

    private val declaration = querySchema {
        field("state.name") { types(QueryValueType.STRING) }
        field("state.day") {
            types(QueryValueType.STRING)
            semantic(Temporal.Date)
        }
        field("state.active") { types(QueryValueType.BOOLEAN) }
        field("state.score") { types(QueryValueType.INTEGER, QueryValueType.DECIMAL) }
        field("state.either") { types(QueryValueType.STRING, QueryValueType.INTEGER) }
        field("state.labels") { values { types(QueryValueType.STRING) } }
        field("state.codes") { items { types(QueryValueType.INTEGER) } }
        field("state.matrix") { items { items { types(QueryValueType.INTEGER) } } }
        field("state.address") {
            kind(QueryValueKind.OBJECT)
            property("city") { types(QueryValueType.STRING) }
            property("geo") {
                kind(QueryValueKind.OBJECT)
                property("lat") { types(QueryValueType.DECIMAL) }
            }
        }
        field("state.parts") {
            items {
                kind(QueryValueKind.OBJECT)
                property("code") { types(QueryValueType.STRING) }
            }
        }
        field("state.flat") {
            kind(QueryValueKind.OBJECT)
            property("inner") { types(QueryValueType.STRING) }
        }
    }

    private fun snapshot(id: String, state: String) = SnapshotRecord.of(
        ref = type.ref("tenant", id),
        version = 1,
        schemaFingerprint = "it",
        state = state,
        firstEventTime = 1,
        lastEventTime = 1,
    )

    @Test
    fun `each logical value maps to its kind, and a field mapped otherwise is kept`(): Unit = runBlocking {
        store.createIndex(type.name)
        // Mapped before as a nested field and a keyword: kept as they are and reported.
        ElasticsearchFixture.client.indices().putMapping(
            PutMappingRequest.of {
                it.index(index).withJson(
                    StringReader(
                        """{"properties":{"state":{"properties":{"flat":{"type":"nested"},"active":{"type":"keyword"},""" +
                            """"address":{"properties":{"geo":{"properties":{"lat":{"type":"double"}}}}}}}}}""",
                    ),
                )
            },
        ).get()
        val queries = ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names)
        val report = queries.manageIndexes(type, schema(ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names, me.ahoo.wow.spi.Ddl.NONE), declaration), emptyList())
        report.invalid.assert().containsExactlyInAnyOrder("state.flat", "state.active")
        report.created.assert().contains("state.name", "state.day", "state.score", "state.labels", "state.codes", "state.matrix", "state.address.city", "state.parts", "state.parts.code", "state.flat.inner")
        report.created.assert().doesNotContain("state.either", "state.address.geo.lat")
        val mapping = ElasticsearchIndexMappingResolver(ElasticsearchFixture.client).refresh(index)
        mapping.fields.getValue("state.day").kind.assert().isEqualTo(Property.Kind.Date)
        mapping.fields.getValue("state.score").kind.assert().isEqualTo(Property.Kind.Double)
        mapping.fields.getValue("state.labels").kind.assert().isEqualTo(Property.Kind.Flattened)
        mapping.fields.getValue("state.codes").kind.assert().isEqualTo(Property.Kind.Long)
        mapping.fields.getValue("state.matrix").kind.assert().isEqualTo(Property.Kind.Long)
        mapping.fields.getValue("state.parts").kind.assert().isEqualTo(Property.Kind.Nested)
        mapping.fields.getValue("state.flat.inner").kind.assert().isEqualTo(Property.Kind.Keyword)
        mapping.fields.containsKey("state.either").assert().isFalse()
        // Validate reports, without creating, what a new schema lacks.
        val validating = ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names, me.ahoo.wow.spi.Ddl.VALIDATE)
        val wider = querySchema {
            field("state.name") { types(QueryValueType.STRING) }
            field("state.extra") { types(QueryValueType.BOOLEAN) }
        }
        validating.manageIndexes(type, schema(validating, wider), emptyList()).missing.assert().containsExactly("state.extra")
    }

    @Test
    fun `a list streams page after page past its batch`(): Unit = runBlocking {
        val queries = ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names)
        queries.prepare(type)
        (1..5).forEach { store.save(snapshot("t$it", """{"name":"N$it"}""")) }
        val schema = schema(queries, declaration)
        val backend = ElasticsearchQueryBackend(type, ElasticsearchFixture.client, index, SnapshotDocumentsForTest, batchSize = 2)
        val sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC))
        backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, sort = sort), schema)).toList()
            .map { it.path("aggregateId").stringValue() }.assert().containsExactly("t1", "t2", "t3", "t4", "t5")
        backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, sort = sort, limit = 3), schema)).toList()
            .map { it.path("aggregateId").stringValue() }.assert().containsExactly("t1", "t2", "t3")
        backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, sort = sort, limit = 4), schema)).toList()
            .size.assert().isEqualTo(4)
    }

    @Test
    fun `date and epoch-second fields group by calendar units and parts`(): Unit = runBlocking {
        val queries = ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names)
        queries.prepare(type)
        val dates = querySchema {
            field("state.day") {
                types(QueryValueType.STRING)
                semantic(Temporal.Date)
            }
            field("state.at") {
                types(QueryValueType.INTEGER)
                temporalEpoch(java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val schema = schema(queries, dates)
        store.save(snapshot("d1", """{"day":"2024-01-01T10:00:00Z","at":1704103200}"""))
        store.save(snapshot("d2", """{"day":"2024-01-02T11:00:00Z","at":1704193200}"""))
        val reloaded = schema(queries, dates)
        val backend = queries.binding(type).backend
        suspend fun groups(group: AggregationGroup, vararg metrics: AggregationMetric) = backend.aggregate(
            QueryAdmission.Trusted.aggregate(AggregationQuery(groupBy = listOf(group), metrics = metrics.toList().ifEmpty { listOf(AggregationMetric.Count("count")) }), reloaded),
        ).toList()
        groups(AggregationGroup.DateHistogram(QueryField("state.day"), "day", AggregationDateUnit.DAY)).size.assert().isEqualTo(2)
        groups(AggregationGroup.DateHistogram(QueryField("state.day"), "second", AggregationDateUnit.SECOND)).size.assert().isEqualTo(2)
        groups(AggregationGroup.DateHistogram(QueryField("state.at"), "month", AggregationDateUnit.MONTH)).single()
            .path("count").longValue().assert().isEqualTo(2L)
        groups(AggregationGroup.DatePart(QueryField("state.at"), "hour", AggregationDatePart.HOUR_OF_DAY)).size.assert().isEqualTo(2)
        groups(AggregationGroup.DatePart(QueryField("state.day"), "weekday", AggregationDatePart.DAY_OF_WEEK)).size.assert().isEqualTo(2)
        groups(
            AggregationGroup.Terms(QueryField("state.day"), "key"),
            AggregationMetric.Numeric(
                AggregationFunction.MAX,
                AggregationExpression.DateDiff(QueryField("state.day"), QueryField("state.at"), DateDiffUnit.HOUR),
                "lag",
            ),
            AggregationMetric.Last(QueryField("state.day"), "last", QueryField("state.at")),
        ).map { it.path("lag").doubleValue() }.assert().containsExactly(0.0, 0.0)
        schema.provisional.assert().isFalse()
    }

    private object SnapshotDocumentsForTest : ElasticsearchQueryDocuments {
        override val tieBreakers: List<String> = listOf("tenantId", "aggregateId")
        override val storeFields: List<String> = me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.STORE_FIELDS
        override fun record(source: tools.jackson.databind.node.ObjectNode) =
            me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.record(source)
    }
}
