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

package me.ahoo.wow.migrate

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.runtime.access.AccessSchema
import me.ahoo.wow.runtime.access.AccessWalking
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalk.Companion.IMPORTING
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID

/**
 * The guides' flow on PostgreSQL: the V10 node starts on standby, which creates its types' tables (its query indexes
 * need them) and so records their walks completed for its schema; the import then appends streams without attributes
 * and claims the walk in that one's place; the node, activated, walks them.
 */
class ImportOnStandbyWalkTest {
    private val type = NineXExample.orderModel.type
    private val schema = AccessSchema.of(NineXExample.orderModel)

    /** A database of its own on the shared container, so no other test's tables or registry rows are in it. */
    private val dataSource = run {
        val postgres = WowContainers.postgres
        val database = "x119_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        admin.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $database") }
        }
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1"))
            user = postgres.username
            password = postgres.password
        }
    }

    private fun log() = PostgresEventLog(dataSource, NineXExample.CONTEXT, PARTITIONS).also { it.createSchema() }

    @Test
    fun `a node on standby creates the tables, the import claims their walk, and the node walks it once activated`(): Unit =
        runBlocking {
            val nodeLog = log()
            val walking = AccessWalking(
                type.context,
                "service/${type.context}",
                "n-1",
                listOf(NineXExample.orderModel),
                Kernel(nodeLog),
                { nodeLog },
                InMemoryLeaseStore(),
            )
            // The node's assembly, then its start on standby: its query indexes make sure of the table.
            walking.serve()
            nodeLog.createTable(type.name)
            nodeLog.walk(type).assert().isEqualTo(AccessWalk.created(schema))

            // The import, through a log of its own that serves no schema.
            EventStreamImporter(
                InMemoryNineXEventSource(NineXExample.streams()),
                NineXExample.plan,
                { log() },
                InMemoryCheckpointRepository(),
            ).importAll(NineXAggregate("example-service", "order"))
            nodeLog.walk(type).assert().isEqualTo(AccessWalk(IMPORTING, emptyMap()))
            val refs = nodeLog.aggregates(type, null, 10).refs
            refs.assert().isNotEmpty()
            refs.flatMap { nodeLog.read(it).toList() }.map { it.access }.assert().containsOnlyNulls()

            // Activated: its first run claims the import's walk and walks it.
            walking.maintain().assert().isFalse()
            nodeLog.walk(type)!!.let {
                it.schema.assert().isEqualTo(schema)
                it.walked.assert().isEqualTo(refs.size.toLong())
                it.completed.assert().isTrue()
            }
            refs.flatMap { nodeLog.read(it).toList() }.map { it.access }.assert().doesNotContainNull()
        }

    private companion object {
        const val PARTITIONS = 4
    }
}
