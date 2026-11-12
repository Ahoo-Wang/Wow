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

    /** The log of [ROWS] streams of [type] and its planned access indexes, created when [create] says. */
    private suspend fun planned(create: Boolean = true): Pair<MongoEventLog, List<MongoQueryIndex>> {
        val log = MongoLogFixture.log(PARTITIONS, type.context, listOf(type.name))
        for (chunk in (0 until ROWS).chunked(BATCH)) {
            log.appendAll(chunk.map { stream(it) to null })
        }
        val declarations = QueryIndexDeclaration.accessOf(manifest, type.context, type.name) +
            QueryIndexDeclaration.BUILT_IN_ACCESS
        val indexes = MongoQueryIndexes.planLog(type.context, type.name, QueryIndexPlan.of(schema(), declarations))
        if (create) {
            MongoQueryIndexes.manage(database, Ddl.AUTO, indexes) { log.createCollection(type.name) }
        }
        return log to indexes
    }

    private fun collection() = database.getCollection(MongoNames.of(MongoEventLog.PREFIX, type.context, type.name))

    @Test
    fun `the access indexes are created as ddl says, the built-ins' indexes of an earlier version replaced`(): Unit =
        runBlocking {
            val (log, indexes) = planned(create = false)
            indexes.map { it.keys }.assert().containsExactly(
                listOf("tenant_id", "access.region"),
                listOf("tenant_id", "access.teams"),
                listOf("access_key.owner"),
                listOf("access_key.space"),
            )
            indexes.filter { it.sparse }.map { it.name }.assert().containsExactly("access_owner", "access_space")
            // A collection an earlier version wrote keeps the built-ins' indexes under the same names.
            val partial = Document("version", Document("\$exists", true))
            listOf("owner", "space").forEach {
                val options = IndexOptions().name("access_$it").partialFilterExpression(partial)
                collection().createIndex(Document("tenant_id", 1).append("access.$it", 1), options)
            }
            val ensured = { _: MongoQueryIndex -> log.createCollection(type.name) }
            val invalid = MongoQueryIndexReport(
                missing = listOf("access_region", "access_teams"),
                invalid = listOf("access_owner", "access_space"),
            )
            MongoQueryIndexes.manage(database, Ddl.VALIDATE, indexes, ensured).assert().isEqualTo(invalid)
            MongoQueryIndexes.manage(database, Ddl.AUTO, indexes, ensured).created.assert()
                .containsExactlyInAnyOrderElementsOf(indexes.map { it.name })
            MongoQueryIndexes.manage(database, Ddl.VALIDATE, indexes, ensured)
                .assert().isEqualTo(MongoQueryIndexReport())
        }

    @Test
    fun `the queries under a policy read the access indexes, a built-in's equality its tenant-scoped key`(): Unit =
        runBlocking {
            planned()
            val schema = schema()
            fun plan(filter: FilterExpression, tenants: List<String>? = listOf("t3")): String =
                collection().find(compiled(schema, filter, tenants)).limit(10).explain()
                    .get("queryPlanner", Document::class.java).get("winningPlan", Document::class.java).toJson()
            fun uses(name: String) = "\"indexName\": \"$name\""
            val single = QueryField("access.region")
            val multiple = QueryField("access.teams")
            val owner = QueryField("access.owner")
            val space = QueryField("access.space")

            plan(EqualFilter(owner, "u-7")).assert().contains(uses("access_owner"))
            plan(EqualFilter(space, "s-7")).assert().contains(uses("access_space"))
            plan(EqualFilter(single, "R7")).assert().contains(uses("access_region"))
            plan(InFilter(single, listOf("R7", "R8"))).assert().contains(uses("access_region"))
            plan(StartsWithFilter(single, "R1")).assert().contains(uses("access_region"))
            plan(EqualFilter(multiple, "K7")).assert().contains(uses("access_teams"))
            // A policy ORs its branches: each takes its own attribute's index.
            plan(OrFilter(listOf(EqualFilter(owner, "u-7"), EqualFilter(multiple, "K7"))))
                .assert().contains(uses("access_owner")).contains(uses("access_teams"))
            // A built-in's `$in` reads the keys of the tenant or tenants the query requires.
            plan(InFilter(owner, listOf("u-7", "u-8"))).assert().contains(uses("access_owner"))
                .contains(MongoAccess.key("t3", "u-7")).contains(MongoAccess.key("t3", "u-8"))
            plan(InFilter(owner, listOf("u-7")), tenants = listOf("t3", "t4")).assert().contains(uses("access_owner"))
                .contains(MongoAccess.key("t3", "u-7")).contains(MongoAccess.key("t4", "u-7"))
            // As intended: a query of every tenant has no tenant to scope a key with, and reads the attribute itself;
            // a negation and `isNull` read the attribute too, through another index's tenant range or a scan.
            plan(EqualFilter(owner, "u-7"), tenants = null).assert().doesNotContain(uses("access_owner"))
            plan(AndFilter(listOf(IsNotNullFilter(owner), NorFilter(listOf(EqualFilter(owner, "u-7"))))))
                .assert().doesNotContain(uses("access_owner"))
            plan(IsNullFilter(space)).assert().doesNotContain(uses("access_space"))
            plan(OrFilter(listOf(EqualFilter(owner, "u-7"), IsNullFilter(space))))
                .assert().doesNotContain(uses("access_owner"))
        }

    @Test
    fun `a stream without an owner or a space adds no entry to their indexes, and every query finds its streams`():
        Unit = runBlocking {
        planned()
        val schema = schema()
        val keys = database.runCommand(Document("validate", collection().namespace.collectionName))
            .get("keysPerIndex", Document::class.java)
        val stamped = (0 until ROWS).filter { it % UNSTAMPED != 0 }
        keys.getInteger("access_owner").assert().isEqualTo(stamped.count { it % NO_SCOPE != 0 })
        keys.getInteger("access_space").assert().isEqualTo(stamped.count { it % NO_SCOPE != 0 })
        // A declared attribute's index, partial on the streams alone, holds every stream.
        keys.getInteger("access_region").assert().isEqualTo(ROWS)
        // The equality finds what it found before: the key is the tenant's and the value's.
        val owner = EqualFilter(QueryField("access.owner"), "u-7")
        collection().countDocuments(compiled(schema, owner)).assert()
            .isEqualTo(stamped.count { it % 2 == 0 && it % NO_SCOPE != 0 && it % OWNERS == 7 }.toLong())
        // `isNull` matches the attribute left out as a null, as it does a stream not stamped (a policy's query
        // guards those out with `access` IS NOT NULL; this filter is the bare one).
        collection().countDocuments(compiled(schema, IsNullFilter(QueryField("access.space")))).assert()
            .isEqualTo((0 until ROWS).count { it % 2 == 0 && (it % NO_SCOPE == 0 || it % UNSTAMPED == 0) }.toLong())
    }

    @Test
    fun `a stream without an owner or a space reads back with both null, appended, stamped or written earlier`():
        Unit = runBlocking {
        val log = MongoLogFixture.log(PARTITIONS, type.context, listOf(type.name))
        fun document(id: String) = checkNotNull(collection().find(Filters.eq("aggregate_id", id)).first())
        val appended = stream(6)
        log.append(appended)
        document("o-6").get("access", Document::class.java).keys.assert().containsExactly("region", "teams")
        document("o-6").containsKey(MongoEventLog.ACCESS_KEY).assert().isFalse()
        suspend fun read() = log.read(appended.ref).toList().single().access
        read().assert().isEqualTo(appended.access).containsKeys("owner", "space")
        // The stream of an owner and a space keeps their tenant-scoped keys, which the SPI never sees.
        val owned = stream(8)
        log.append(owned)
        val keys = Document("owner", MongoAccess.key("t3", "u-8")).append("space", MongoAccess.key("t3", "s-8"))
        document("o-8").get(MongoEventLog.ACCESS_KEY, Document::class.java).assert().isEqualTo(keys)
        log.read(owned.ref).toList().single().access.assert().isEqualTo(owned.access)

        val restamped = checkNotNull(stream(6, region = "R9").access)
        log.stamp(appended.ref, restamped, 1, 1, 2).assert().isEqualTo(1)
        document("o-6").get("access", Document::class.java).keys.assert().containsExactly("region", "teams")
        read().assert().isEqualTo(restamped)

        // A stream an earlier version wrote holds the nulls: it reads the same, and `isNull` matches it.
        val nulls = Document(
            "owner",
            null
        ).append("space", null).append("region", "R9").append("teams", listOf("K6"))
        collection().updateOne(Filters.eq("aggregate_id", "o-6"), Updates.set("access", nulls))
        read().assert().isEqualTo(restamped)
        val noSpace = compiled(schema(), IsNullFilter(QueryField("access.space")))
        collection().countDocuments(noSpace).assert().isEqualTo(1L)

        // One an earlier version wrote with an owner has no key: an equality on its owner does not find it, until
        // a stamp writes its attributes again, and its key with them.
        collection().updateOne(Filters.eq("aggregate_id", "o-8"), Updates.unset(MongoEventLog.ACCESS_KEY))
        val ownedBy = EqualFilter(QueryField("access.owner"), "u-8")
        val byOwner = compiled(schema(), ownedBy)
        collection().countDocuments(byOwner).assert().isEqualTo(0L)
        // A negation never lets it through: `not(owner eq u-8)`, as a policy writes it, reads the owner itself.
        val notOwnedBy = AndFilter(listOf(IsNotNullFilter(QueryField("access.owner")), NorFilter(listOf(ownedBy))))
        collection().countDocuments(compiled(schema(), notOwnedBy)).assert().isEqualTo(0L)
        log.stamp(owned.ref, checkNotNull(owned.access), 1, 1, 2).assert().isEqualTo(1)
        collection().countDocuments(byOwner).assert().isEqualTo(1L)
    }

/** The `events` schema of [type] over this log, as the query engine compiles it. */
    private suspend fun schema() = QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
        .compile(
            QuerySchemaContext(type, QueryModel.EVENTS),
            MongoEventQueryStorage(
                MongoLogFixture.client,
                MongoLogFixture.DATABASE,
                type.context
            ).binding(type)!!.storage,
        )
        .schema()

/**
     * [filter] in [tenants], every tenant when null, compiled as an `events` query is, with the streams' scope, the
     * indexes' partial filter.
     */
    private fun compiled(
        schema: QueryModelSchema,
        filter: FilterExpression,
        tenants: List<String>? = listOf(
            "t3"
        )
    ): Bson {
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
