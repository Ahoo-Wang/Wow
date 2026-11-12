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

package me.ahoo.wow.postgres.query

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
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
import me.ahoo.wow.postgres.PostgresEventLogTables
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The access indexes of a log table: created as `ddl` says, and taken by the `events` model's queries under an access
 * policy, each attribute's own, a B-tree of a single one and a GIN index of a multiple one.
 */
class PostgresEventQueryIndexesTest {
    private val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

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
            val log = PostgresFixture.log(PARTITIONS, type.context, listOf(type.name))
            for (chunk in (0 until ROWS).chunked(BATCH)) {
                log.appendAll(chunk.map { Append.of(stream(it)) })
            }
            val table = PostgresEventLogTables(type.context).aggregate(type.name).log
            val binding = PostgresEventQueryStorage(PostgresFixture.dataSource, type.context).binding(type)!!
            val schema = QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
                .compile(QuerySchemaContext(type, QueryModel.EVENTS), binding.storage)
                .schema()
            val declarations = QueryIndexDeclaration.accessOf(manifest, type.context, type.name) +
                QueryIndexDeclaration.BUILT_IN_ACCESS
            val indexes = PostgresQueryIndexes.planLog(type.context, type.name, QueryIndexPlan.of(schema, declarations))
            val names = indexes.map { it.name }
            PostgresQueryIndexes.manage(PostgresFixture.dataSource, Ddl.VALIDATE, indexes).missing.assert()
                .containsExactlyInAnyOrderElementsOf(names)
            PostgresQueryIndexes.manage(PostgresFixture.dataSource, Ddl.AUTO, indexes).created.assert()
                .containsExactlyInAnyOrderElementsOf(names)
            PostgresQueryIndexes.manage(PostgresFixture.dataSource, Ddl.VALIDATE, indexes).assert()
                .isEqualTo(PostgresQueryIndexReport())
            // The statistics a log of this size has, so the planner weighs a tenant's streams as it would.
            PostgresFixture.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("ANALYZE $table") }
            }

            val region = names[0]
            val teams = names[1]
            val owner = names[2]
            val space = names[3]
            val compiler = PostgresSnapshotSqlCompiler(table, PostgresEventQueryLayout.EVENTS)
            fun scoped(filter: FilterExpression): FilterExpression = AndFilter(listOf(TenantIdFilter("t3"), filter))
            fun list(filter: FilterExpression): String =
                explain(compiler.list(QueryAdmission.Trusted.list(ListQuery(scoped(filter), limit = 10), schema)))
            fun count(filter: FilterExpression): String {
                val counted = QueryAdmission.Trusted.count(CountQuery(scoped(filter), 10), schema)
                return explain(compiler.count(counted, counted.query.filter, null))
            }
            val single = QueryField("access.region")
            val multiple = QueryField("access.teams")

            list(EqualFilter(QueryField("access.owner"), "u-7")).assert().contains(owner)
            list(EqualFilter(QueryField("access.space"), "s-7")).assert().contains(space)
            list(EqualFilter(single, "R7")).assert().contains(region)
            list(InFilter(single, listOf("R7", "R8"))).assert().contains(region)
            list(StartsWithFilter(single, "R1")).assert().contains(region)
            count(EqualFilter(multiple, "K7")).assert().contains(teams)
            count(InFilter(multiple, listOf("K7", "K8"))).assert().contains(teams)
            // A policy ORs its branches: each takes its own attribute's index.
            count(OrFilter(listOf(EqualFilter(QueryField("access.owner"), "u-7"), EqualFilter(multiple, "K7"))))
                .assert().contains(owner).contains(teams)
        }

    private fun explain(statement: SqlStatement): String = PostgresFixture.dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
            SqlStatement("EXPLAIN " + statement.sql, statement.parameters).prepare(connection).use { explain ->
                explain.executeQuery().use { rows ->
                    buildString { while (rows.next()) appendLine(rows.getString(1)) }
                }
            }
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
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
