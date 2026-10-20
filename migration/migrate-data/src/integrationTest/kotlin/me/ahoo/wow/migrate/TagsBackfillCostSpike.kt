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

import com.mongodb.client.MongoClients
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.TimeSource

/**
 * The cost of the tags backfill (R4-Q2c) on [AGGREGATES] aggregates of [STREAMS] streams each, appended without tags,
 * on PostgreSQL and MongoDB in containers: the per-aggregate cost the coordinator asked for. Not a test: run with
 * `./gradlew :migrate-data:integrationTest --tests '*TagsBackfillCostSpike*' -Dwow.spike.tags=true`.
 */
class TagsBackfillCostSpike {
    class Parcel(var region: String = "")

    class Moved(val region: String)

    @Test
    fun `backfill cost per aggregate on PostgreSQL and MongoDB`() {
        assumeTrue(System.getProperty("wow.spike.tags") == "true", "Run with -Dwow.spike.tags=true")
        val postgres = HikariDataSource().apply {
            jdbcUrl = WowContainers.postgres.jdbcUrl
            username = WowContainers.postgres.username
            password = WowContainers.postgres.password
            maximumPoolSize = POOL
        }
        measure("postgres") { context -> PostgresEventLog(postgres, context, PARTITIONS).also { it.createSchema() } }
        val mongo = MongoClients.create(WowContainers.mongo.replicaSetUrl)
        measure("mongo") { context -> MongoEventLog(mongo, "tags_spike", context, PARTITIONS).also { it.createSchema() } }
    }

    private fun measure(backend: String, logOf: (String) -> EventLog) = runBlocking {
        val context = "spike_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)
        val model = aggregate<Parcel>(context, "parcel") {
            initial { Parcel() }
            evolve<Moved> { region = it.region }
            tags { mapOf("region" to listOfNotNull(it.region.takeIf(String::isNotEmpty))) }
        }
        val codec = EventCodec()
        val log = logOf(context)
        val seeding = TimeSource.Monotonic.markNow()
        val byPartition = (0 until AGGREGATES).groupBy { Partitioner.partitionOf(model.type.ref("tenant", "p-$it"), PARTITIONS) }
        (1..STREAMS).forEach { version ->
            byPartition.values.flatMap { it.chunked(BATCH) }.forEach { ids ->
                log.appendAll(
                    ids.map { id ->
                        EventStreamRecord(
                            ref = model.type.ref("tenant", "p-$id"),
                            version = version,
                            commandId = "p-$id-$version",
                            requestId = "p-$id-$version",
                            commandType = "$context.parcel.Move",
                            commitTime = version.toLong(),
                            events = listOf(codec.encode(model, Moved(if (version % 2 == 0) "west" else "east"))),
                            scope = Scope.NONE,
                        ) to null
                    },
                )
            }
        }
        val seeded = seeding.elapsedNow()
        val backfiller = TagsBackfiller(MigrationModels(listOf(model)), { log }, InMemoryCheckpointRepository())
        val started = TimeSource.Monotonic.markNow()
        val progress = backfiller.backfill(model.type)
        val took = started.elapsedNow()
        println(
            "TAGS-SPIKE $backend: $AGGREGATES aggregates x $STREAMS streams (seeded in $seeded); backfill ${progress.render()}; " +
                "wall ${took.inWholeMilliseconds} ms = ${"%.3f".format(took.inWholeMicroseconds / 1000.0 / AGGREGATES)} ms " +
                "per aggregate (page 500, concurrency 8)",
        )
    }

    private companion object {
        const val AGGREGATES = 100_000
        const val STREAMS = 2
        const val BATCH = 500
        const val PARTITIONS = 8
        const val POOL = 16
        const val CONTEXT_SUFFIX = 12
    }
}
