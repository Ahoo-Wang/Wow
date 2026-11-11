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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.bson.Document
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The access indexes of a log collection: created as `ddl` says, partial on the streams, and taken by the `events`
 * model's queries under an access policy, a multikey one for a multiple attribute.
 */
class MongoEventQueryIndexesTest {
    private val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
    private val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)

    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                type.context,
                aggregates = listOf(
                    AggregateManifest(
                        type.name,
                        state = StateManifest(Manifest.schemaRef("it.OrderState")),
                        access = listOf(
                            AccessAttributeManifest("region", AccessAttributeManifest.Type.STRING),
                            AccessAttributeManifest("teams", AccessAttributeManifest.Type.STRING, multiple = true),
                        ),
                    ),
                ),
            ),
        ),
        defs = mapOf("it.OrderState" to mapOf("type" to "object")),
    )

    /** Stream `n`, the first of its aggregate, of an owner and a space among a hundred; every fiftieth not stamped. */
    private fun stream(n: Int): EventStreamRecord {
        val scope = Scope("u-${n % OWNERS}", "s-${n % OWNERS}")
        val access = AccessValues.ofStream(
            mapOf("region" to "R${n % REGIONS}", "teams" to listOf("K${n % REGIONS}")),
            scope
        )
        return EventStreamRecord(
            ref = type.ref(if (n % 2 == 0) "t3" else "t4", "o-$n"),
            version = 1,
            commandId = "c-$n",
            requestId = "r-$n",
            commandType = "${type.context}.order.Create",
            commitTime = n.toLong(),
            events = listOf(EventRecord("${type.context}.order.Created", 1, "{}")),
            scope = scope,
            access = access.takeUnless { n % UNSTAMPED == 0 },
        )
    }

    @Test
    fun `the access indexes of a log are created as ddl says and serve the queries under a policy`(): Unit =
        runBlocking {
            val log = MongoLogFixture.log(PARTITIONS, type.context, listOf(type.name))
            for (chunk in (0 until ROWS).chunked(BATCH)) {
                log.appendAll(chunk.map { stream(it) to null })
            }
            val binding = MongoEventQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
                .binding(type)!!
            val schema = QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
                .compile(QuerySchemaContext(type, QueryModel.EVENTS), binding.storage)
                .schema()
            val declarations = QueryIndexDeclaration.accessOf(manifest, type.context, type.name) +
                QueryIndexDeclaration.BUILT_IN_ACCESS
            val indexes = MongoQueryIndexes.planLog(type.context, type.name, QueryIndexPlan.of(schema, declarations))
            indexes.map { it.keys }.assert().containsExactly(
                listOf("tenant_id", "access.region"),
                listOf("tenant_id", "access.teams"),
                listOf("tenant_id", "access.owner"),
                listOf("tenant_id", "access.space"),
            )
            val names = indexes.map { it.name }
            val ensured = { _: MongoQueryIndex -> log.createCollection(type.name) }
            MongoQueryIndexes.manage(database, Ddl.VALIDATE, indexes, ensured).missing.assert()
                .containsExactlyInAnyOrderElementsOf(names)
            MongoQueryIndexes.manage(database, Ddl.AUTO, indexes, ensured).created.assert()
                .containsExactlyInAnyOrderElementsOf(names)
            MongoQueryIndexes.manage(database, Ddl.VALIDATE, indexes, ensured)
                .assert().isEqualTo(MongoQueryIndexReport())

            val collection = database.getCollection(indexes.first().collection)
            fun plan(filter: FilterExpression): String {
                val admitted = QueryAdmission.Trusted.list(
                    ListQuery(AndFilter(listOf(TenantIdFilter("t3"), filter)), limit = 10),
                    schema,
                )
                // Every `events` query holds the streams' scope, the indexes' partial filter.
                val streams = Filters.exists("version")
                val filter = Filters.and(streams, MongoMatchCompiler.compile(admitted.query.filter, admitted))
                return collection.find(filter)
                    .limit(10).explain()
                    .get("queryPlanner", Document::class.java).get("winningPlan", Document::class.java).toJson()
            }
            fun uses(name: String) = "\"indexName\": \"$name\""
            val single = QueryField("access.region")
            val multiple = QueryField("access.teams")

            plan(EqualFilter(QueryField("access.owner"), "u-7")).assert().contains(uses("access_owner"))
            plan(EqualFilter(QueryField("access.space"), "s-7")).assert().contains(uses("access_space"))
            plan(EqualFilter(single, "R7")).assert().contains(uses("access_region"))
            plan(InFilter(single, listOf("R7", "R8"))).assert().contains(uses("access_region"))
            plan(StartsWithFilter(single, "R1")).assert().contains(uses("access_region"))
            plan(EqualFilter(multiple, "K7")).assert().contains(uses("access_teams"))
            // A policy ORs its branches: each takes its own attribute's index.
            plan(OrFilter(listOf(EqualFilter(QueryField("access.owner"), "u-7"), EqualFilter(multiple, "K7"))))
                .assert().contains(uses("access_owner")).contains(uses("access_teams"))
        }

    private companion object {
        const val PARTITIONS = 4
        const val ROWS = 2_000
        const val BATCH = 200
        const val OWNERS = 100
        const val REGIONS = 10
        const val UNSTAMPED = 50
    }
}
