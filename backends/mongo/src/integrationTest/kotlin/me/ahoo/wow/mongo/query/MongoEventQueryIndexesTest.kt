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
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.mongo.MongoAccess
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The access indexes of a log collection: created as `ddl` says, partial on the streams, and taken by the `events`
 * model's queries under an access policy, a multikey one for a multiple attribute. A built-in attribute's index is the
 * sparse index of its tenant-scoped key (D420): a stream of an aggregate without an owner or a space leaves both out and
 * adds no entry, reads back with both null, and an equality or an `$in` reads the tenant's keys alone.
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

    /**
     * Stream `n`, the first of its aggregate, of an owner and a space among a hundred, every third of neither (as E1's
     * aggregates are); every fiftieth not stamped.
     */
    private fun stream(n: Int, region: String = "R${n % REGIONS}"): EventStreamRecord {
        val scope = if (n % NO_SCOPE == 0) Scope.NONE else Scope("u-${n % OWNERS}", "s-${n % OWNERS}")
        val access = AccessValues.ofStream(mapOf("region" to region, "teams" to listOf("K${n % REGIONS}")), scope)
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
            val schema = schema()
            val declarations = QueryIndexDeclaration.accessOf(manifest, type.context, type.name) +
                QueryIndexDeclaration.BUILT_IN_ACCESS
            val indexes = MongoQueryIndexes.planLog(type.context, type.name, QueryIndexPlan.of(schema, declarations))
            indexes.map { it.keys }.assert().containsExactly(
                listOf("tenant_id", "access.region"),
                listOf("tenant_id", "access.teams"),
                listOf("access_key.owner"),
                listOf("access_key.space"),
            )
            indexes.filter { it.sparse }.map { it.name }.assert().containsExactly("access_owner", "access_space")
            val names = indexes.map { it.name }
            val ensured = { _: MongoQueryIndex -> log.createCollection(type.name) }
            // A collection of a beta keeps the built-ins' indexes of before D420, under the same names.
            val collection = database.getCollection(indexes.first().collection)
            listOf("owner", "space").forEach {
                collection.createIndex(
                    Document("tenant_id", 1).append("access.$it", 1),
                    IndexOptions().name("access_$it").partialFilterExpression(Document("version", Document("\$exists", true))),
                )
            }
            MongoQueryIndexes.manage(database, Ddl.VALIDATE, indexes, ensured).assert().isEqualTo(
                MongoQueryIndexReport(missing = listOf("access_region", "access_teams"), invalid = listOf("access_owner", "access_space")),
            )
            MongoQueryIndexes.manage(database, Ddl.AUTO, indexes, ensured).created.assert()
                .containsExactlyInAnyOrderElementsOf(names)
            MongoQueryIndexes.manage(database, Ddl.VALIDATE, indexes, ensured)
                .assert().isEqualTo(MongoQueryIndexReport())

            fun plan(filter: FilterExpression, tenants: List<String>? = listOf("t3")): String =
                collection.find(compiled(schema, filter, tenants))
                .limit(10).explain()
                .get("queryPlanner", Document::class.java).get("winningPlan", Document::class.java).toJson()
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

            // A built-in attribute's equality and `$in` read its tenant-scoped key, in the tenant or tenants the query
            // requires (D420); a negation and `isNull` read the attribute itself, through the tenant's range of another
            // index or a scan, as intended (PostgreSQL alike); and a query of every tenant (`tenant=*`) has no tenant
            // to scope a key with, so its equality reads the attribute itself, also as intended.
            val owner = QueryField("access.owner")
            val space = QueryField("access.space")
            plan(InFilter(owner, listOf("u-7", "u-8"))).assert().contains(uses("access_owner"))
                .contains(MongoAccess.key("t3", "u-7")).contains(MongoAccess.key("t3", "u-8"))
            plan(InFilter(owner, listOf("u-7")), tenants = listOf("t3", "t4")).assert().contains(uses("access_owner"))
                .contains(MongoAccess.key("t3", "u-7")).contains(MongoAccess.key("t4", "u-7"))
            plan(EqualFilter(owner, "u-7"), tenants = null).assert().doesNotContain(uses("access_owner"))
            plan(AndFilter(listOf(IsNotNullFilter(owner), NorFilter(listOf(EqualFilter(owner, "u-7"))))))
                .assert().doesNotContain(uses("access_owner"))
            plan(IsNullFilter(space)).assert().doesNotContain(uses("access_space"))
            plan(OrFilter(listOf(EqualFilter(owner, "u-7"), IsNullFilter(space)))).assert()
                .doesNotContain(uses("access_owner"))

            // A stream without an owner or a space adds no entry to their indexes; a declared attribute's index, partial
            // on the streams alone, holds every stream, a stream not stamped keyed by the missing attribute.
            val keys = database.runCommand(Document("validate", collection.namespace.collectionName))
                .get("keysPerIndex", Document::class.java)
            val stamped = (0 until ROWS).filter { it % UNSTAMPED != 0 }
            // The equality finds what it found before: the key is the tenant's and the value's.
            collection.countDocuments(compiled(schema, EqualFilter(owner, "u-7")))
                .assert().isEqualTo(stamped.count { it % 2 == 0 && it % NO_SCOPE != 0 && it % OWNERS == 7 }.toLong())
            keys.getInteger("access_owner").assert().isEqualTo(stamped.count { it % NO_SCOPE != 0 })
            keys.getInteger("access_space").assert().isEqualTo(stamped.count { it % NO_SCOPE != 0 })
            keys.getInteger("access_region").assert().isEqualTo(ROWS)
            // `isNull` still finds them all: the attribute left out matches it as a null does, as a stream not stamped
            // does (a policy's query guards those out with `access` IS NOT NULL; this filter is the bare one).
            collection.countDocuments(compiled(schema, IsNullFilter(space))).assert()
                .isEqualTo((0 until ROWS).count { it % 2 == 0 && (it % NO_SCOPE == 0 || it % UNSTAMPED == 0) }.toLong())
        }

    @Test
    fun `a stream without an owner or a space reads back with both null, appended, stamped or written before D420`():
        Unit = runBlocking {
            val log = MongoLogFixture.log(PARTITIONS, type.context, listOf(type.name))
            val appended = stream(6)
            log.append(appended)
            val collection = database.getCollection(MongoNames.of(MongoEventLog.PREFIX, type.context, type.name))
            fun stored() = checkNotNull(collection.find(Filters.eq("aggregate_id", "o-6")).first())
                .get("access", Document::class.java)
            stored().keys.assert().containsExactly("region", "teams")
            fun keyed() = checkNotNull(collection.find(Filters.eq("aggregate_id", "o-6")).first())
                .get(MongoEventLog.ACCESS_KEY, Document::class.java)
            keyed().assert().isNull()
            suspend fun read() = log.read(appended.ref).toList().single().access
            read().assert().isEqualTo(appended.access).containsKeys("owner", "space")
            // The stream of an owner and a space keeps their tenant-scoped keys, which the SPI never sees.
            val owned = stream(8)
            log.append(owned)
            checkNotNull(collection.find(Filters.eq("aggregate_id", "o-8")).first())
                .get(MongoEventLog.ACCESS_KEY, Document::class.java).assert()
                .isEqualTo(Document("owner", MongoAccess.key("t3", "u-8")).append("space", MongoAccess.key("t3", "s-8")))
            log.read(owned.ref).toList().single().access.assert().isEqualTo(owned.access)

            val restamped = checkNotNull(stream(6, region = "R9").access)
            log.stamp(appended.ref, restamped, 1, 1, 2).assert().isEqualTo(1)
            stored().keys.assert().containsExactly("region", "teams")
            read().assert().isEqualTo(restamped)

            // A stream written before D420 holds the nulls: it reads the same, and `isNull` matches it.
            collection.updateOne(
                Filters.eq("aggregate_id", "o-6"),
                Updates.set("access", Document("owner", null).append("space", null).append("region", "R9")
                    .append("teams", listOf("K6"))),
            )
            read().assert().isEqualTo(restamped)
            collection.countDocuments(compiled(schema(), IsNullFilter(QueryField("access.space")))).assert().isEqualTo(1L)

            // One written before D420 with an owner has no key: an equality on its owner does not find it, until a
            // stamp writes its attributes again, and its key with them.
            collection.updateOne(Filters.eq("aggregate_id", "o-8"), Updates.unset(MongoEventLog.ACCESS_KEY))
            val byOwner = compiled(schema(), EqualFilter(QueryField("access.owner"), "u-8"))
            collection.countDocuments(byOwner).assert().isEqualTo(0L)
            log.stamp(owned.ref, checkNotNull(owned.access), 1, 1, 2).assert().isEqualTo(1)
            collection.countDocuments(byOwner).assert().isEqualTo(1L)
        }

    /** The `events` schema of [type] over this log, as the query engine compiles it. */
    private suspend fun schema() = QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
        .compile(
            QuerySchemaContext(type, QueryModel.EVENTS),
            MongoEventQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context).binding(type)!!.storage,
        )
        .schema()

    /**
     * [filter] in [tenants], every tenant when null, compiled as an `events` query is, with the streams' scope, the
     * indexes' partial filter.
     */
    private fun compiled(schema: QueryModelSchema, filter: FilterExpression, tenants: List<String>? = listOf("t3")): Bson {
        val required = tenants?.map(::TenantIdFilter)?.let { it.singleOrNull() ?: OrFilter(it) }
        val admitted = QueryAdmission.Trusted.list(
            ListQuery(AndFilter(listOfNotNull(required, filter)), limit = 10),
            schema,
        )
        return Filters.and(Filters.exists("version"), MongoMatchCompiler.compile(admitted.query.filter, admitted))
    }

    private companion object {
        const val PARTITIONS = 4
        const val ROWS = 2_000
        const val BATCH = 200
        const val OWNERS = 100
        const val REGIONS = 10
        const val UNSTAMPED = 50
        const val NO_SCOPE = 3
    }
}
