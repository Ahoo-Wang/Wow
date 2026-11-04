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

package me.ahoo.wow.postgres.snapshot

import com.github.dockerjava.api.model.ExposedPort
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/**
 * The snapshot subscription's snapshot writes commit without waiting for their WAL flush
 * ([PostgresSnapshotStore.saveAll]), and the checkpoint it saves after them waits for its own: PostgreSQL writes its WAL
 * in order, so the checkpoint's flush carries the snapshots before it, and a checkpoint that survives a crash never
 * covers a snapshot that did not. On a PostgreSQL of its own, killed without a checkpoint (SIGKILL: no shutdown, the WAL
 * buffers lost) and started again on its data, with the WAL writer slowed (10 s) so that it writes nothing of its own
 * in between and autovacuum off so that no other commit flushes.
 */
class PostgresSnapshotDurabilityTest {
    @Test
    fun `a checkpoint that survives a crash should cover snapshots that survive it too`() {
        PostgreSQLContainer(WowContainers.POSTGRES_IMAGE)
            .withCommand("postgres", "-c", "wal_writer_delay=10s", "-c", "autovacuum=off")
            .use { container ->
                container.start()
                val context = "durability"
                val type = AggregateType(context, "account")
                var covered = emptyList<SnapshotRecord>()
                pooled(container.jdbcUrl, container) { dataSource ->
                    val snapshots = PostgresSnapshotStore(dataSource, context).also { it.createSchema() }
                    val checkpoints = PostgresCheckpointStore(dataSource).also { it.createSchema() }
                    // The aggregate's table first, by a save that waits for its flush.
                    runBlocking { snapshots.save(snapshot(type, "table")).assert().isTrue() }
                    // A batch whose WAL is not even written out of PostgreSQL's buffers, which a kill loses, so that
                    // only the checkpoint's flush can make it durable: should the WAL writer write one first, the next.
                    var coveredEnd = ""
                    for (attempt in 1..ATTEMPTS) {
                        covered = (1..3).map { snapshot(type, "covered-$attempt-$it") }
                        runBlocking {
                            snapshots.saveAll(covered).forEach { it.assert().isEqualTo(SnapshotSaveOutcome.Written) }
                        }
                        coveredEnd = insertedTo(dataSource)
                        if (before(dataSource, WRITTEN, coveredEnd)) break
                    }
                    before(dataSource, WRITTEN, coveredEnd).assert().isTrue()
                    runBlocking { checkpoints.saveAll(GROUP, listOf(Checkpoint(SUBSCRIPTION, 0, COVERED_POSITION))) }
                    // The checkpoint's commit flushed the WAL up to it, the snapshots' records with it.
                    before(dataSource, FLUSHED, coveredEnd).assert().isFalse()
                }
                crash(container)
                pooled(restartedUrl(container), container) { dataSource ->
                    val snapshots = PostgresSnapshotStore(dataSource, context)
                    val checkpoints = PostgresCheckpointStore(dataSource)
                    runBlocking {
                        checkpoints.load(GROUP, SUBSCRIPTION, 0).assert().isEqualTo(COVERED_POSITION)
                        covered.map { snapshots.load(it.ref)?.version }.assert().containsExactly(1, 1, 1)
                    }
                }
                // PostgreSQL recovered from its WAL: it was not shut down.
                container.logs.assert().contains("automatic recovery in progress")
            }
    }

    private fun snapshot(type: AggregateType, id: String) = SnapshotRecord(
        ref = type.ref("tenant", id),
        version = 1,
        schemaFingerprint = "fingerprint",
        state = """{"id":"$id"}""",
        firstEventTime = 1,
        lastEventTime = 1,
    )

    /** Whether PostgreSQL's WAL [position] ([WRITTEN] or [FLUSHED]) is still before [lsn]. */
    private fun before(dataSource: DataSource, position: String, lsn: String): Boolean = dataSource.connection.use {
        it.prepareStatement("SELECT $position < ?::pg_lsn").use { query ->
            query.setString(1, lsn)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getBoolean(1)
            }
        }
    }

    /** How far PostgreSQL has inserted its WAL: past every record committed so far. */
    private fun insertedTo(dataSource: DataSource): String = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_current_wal_insert_lsn()::text").use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
    }

    /** Kills the container's PostgreSQL (SIGKILL), then starts it again on the data it left. */
    private fun crash(container: PostgreSQLContainer) {
        container.dockerClient.killContainerCmd(container.containerId).exec()
        container.dockerClient.startContainerCmd(container.containerId).exec()
    }

    /** The restarted container's JDBC URL: Docker gives its port a host port again. */
    private fun restartedUrl(container: PostgreSQLContainer): String {
        val port = container.dockerClient.inspectContainerCmd(container.containerId).exec()
            .networkSettings.ports.bindings.getValue(ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT))
            .first().hostPortSpec
        return "jdbc:postgresql://${container.host}:$port/${container.databaseName}"
    }

    /** Runs [block] with a small pool on [url], once PostgreSQL there answers (it may still be recovering). */
    private fun pooled(url: String, container: PostgreSQLContainer, block: (DataSource) -> Unit) {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = container.username
                password = container.password
                maximumPoolSize = 2
                initializationFailTimeout = READY_MILLIS
            },
        ).use(block)
    }

    private companion object {
        const val GROUP = "durability"
        const val SUBSCRIPTION = "wow.snapshot"
        const val COVERED_POSITION = 3L
        const val READY_MILLIS = 60_000L
        const val ATTEMPTS = 5

        /** How far the WAL is written to the operating system, which a kill of PostgreSQL does not lose. */
        const val WRITTEN = "pg_current_wal_lsn()"

        /** How far the WAL is flushed to the disk. */
        const val FLUSHED = "pg_current_wal_flush_lsn()"
    }
}
