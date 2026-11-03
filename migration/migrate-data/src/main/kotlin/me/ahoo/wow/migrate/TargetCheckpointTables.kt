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

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import me.ahoo.wow.spi.json.WowJson
import org.bson.Document
import org.bson.types.ObjectId
import java.sql.Connection
import javax.sql.DataSource

/**
 * The [CheckpointTable] on a PostgreSQL target, in its database beside the log: `wow_migrate_checkpoint`, one row per
 * migration, its checkpoints the JSON the checkpoint file holds; and `wow_migrate_imported_key`, one row per key, in
 * the order recorded (`sequence`). A key write reads the fences in the same transaction, `FOR SHARE`, so a write of
 * the checkpoints waits for it, and it for one in progress.
 */
internal class PostgresCheckpointTable(
    private val dataSource: DataSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : CheckpointTable {
    /** Creates the tables when missing, one run at a time: two `CREATE TABLE IF NOT EXISTS` at once may fail. */
    fun createSchema() {
        transaction { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use {
                it.setString(1, CHECKPOINTS)
                it.execute()
            }
            connection.createStatement().use { statement -> DDL.forEach(statement::execute) }
        }
    }

    override fun load(name: String): StoredCheckpoints? = dataSource.connection.use { connection ->
        connection.prepareStatement(LOAD).use { select ->
            select.setString(1, name)
            select.executeQuery().use { row ->
                if (!row.next()) {
                    null
                } else {
                    StoredCheckpoints(
                        WowJson.mapper.readValue(row.getString("checkpoints"), ImportCheckpoints::class.java),
                        row.getLong("version"),
                        fencesOf(row.getString("fences")),
                    )
                }
            }
        }
    }

    override fun write(
        name: String,
        version: Long,
        checkpoints: ImportCheckpoints,
        fences: Map<String, Long>,
    ): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(if (version == 0L) INSERT else UPDATE).use { write ->
            write.setLong(1, version + 1)
            write.setString(2, WowJson.mapper.writeValueAsString(checkpoints))
            write.setString(3, WowJson.mapper.writeValueAsString(fences))
            write.setLong(4, clock())
            write.setString(5, name)
            if (version != 0L) {
                write.setLong(6, version)
            }
            write.executeUpdate() == 1
        }
    }

    override fun addKeys(name: String, keys: List<ImportedKey>, admit: (Map<String, Long>) -> Unit) {
        fenced(name, admit) { connection ->
            connection.prepareStatement(INSERT_KEY).use { insert ->
                keys.forEach { key ->
                    insert.setString(1, name)
                    insert.setString(2, key.name)
                    insert.setString(3, WowJson.mapper.writeValueAsString(key))
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
    }

    override fun keys(name: String, prepareKey: String, after: String?, limit: Int): List<RecordedKey> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(KEYS).use { select ->
                select.setString(1, name)
                select.setString(2, prepareKey)
                select.setLong(3, after?.toLong() ?: 0)
                select.setInt(4, limit)
                select.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                RecordedKey(
                                    rows.getLong("sequence").toString(),
                                    WowJson.mapper.readValue(rows.getString("imported_key"), ImportedKey::class.java),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun removeKeys(name: String, positions: List<String>, admit: (Map<String, Long>) -> Unit) {
        fenced(name, admit) { connection ->
            connection.prepareStatement(REMOVE_KEYS).use { delete ->
                delete.setString(1, name)
                delete.setArray(2, connection.createArrayOf("bigint", positions.map(String::toLong).toTypedArray()))
                delete.executeUpdate()
            }
        }
    }

    /** Runs [write] in one transaction with [admit] of the fences, read `FOR SHARE`: none when nothing is written. */
    private fun fenced(name: String, admit: (Map<String, Long>) -> Unit, write: (Connection) -> Unit) {
        transaction { connection ->
            val fences = connection.prepareStatement(FENCES_FOR_SHARE).use { select ->
                select.setString(1, name)
                select.executeQuery().use { row -> if (row.next()) fencesOf(row.getString("fences")) else emptyMap() }
            }
            admit(fences)
            write(connection)
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    companion object {
        const val CHECKPOINTS = "wow_migrate_checkpoint"
        const val KEYS_TABLE = "wow_migrate_imported_key"

        private val DDL = listOf(
            """
            CREATE TABLE IF NOT EXISTS $CHECKPOINTS (
                name text PRIMARY KEY,
                version bigint NOT NULL,
                checkpoints jsonb NOT NULL,
                fences jsonb NOT NULL,
                updated_at bigint NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS $KEYS_TABLE (
                checkpoint text NOT NULL,
                sequence bigserial NOT NULL,
                prepare_key text NOT NULL,
                imported_key jsonb NOT NULL,
                PRIMARY KEY (checkpoint, sequence)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS ${KEYS_TABLE}_by_name ON $KEYS_TABLE (checkpoint, prepare_key, sequence)",
        )
        private const val LOAD = "SELECT version, checkpoints, fences FROM $CHECKPOINTS WHERE name = ?"
        private const val INSERT = "INSERT INTO $CHECKPOINTS (version, checkpoints, fences, updated_at, name) " +
            "VALUES (?, ?::jsonb, ?::jsonb, ?, ?) ON CONFLICT (name) DO NOTHING"
        private const val UPDATE = "UPDATE $CHECKPOINTS SET version = ?, checkpoints = ?::jsonb, fences = ?::jsonb, " +
            "updated_at = ? WHERE name = ? AND version = ?"
        private const val FENCES_FOR_SHARE = "SELECT fences FROM $CHECKPOINTS WHERE name = ? FOR SHARE"
        private const val INSERT_KEY =
            "INSERT INTO $KEYS_TABLE (checkpoint, prepare_key, imported_key) VALUES (?, ?, ?::jsonb)"
        private const val KEYS = "SELECT sequence, imported_key FROM $KEYS_TABLE " +
            "WHERE checkpoint = ? AND prepare_key = ? AND sequence > ? ORDER BY sequence LIMIT ?"
        private const val REMOVE_KEYS = "DELETE FROM $KEYS_TABLE WHERE checkpoint = ? AND sequence = ANY(?)"
    }
}

/**
 * The [CheckpointTable] on a MongoDB target, a replica set, in its database beside the log: `wow_migrate_checkpoint`,
 * one document per migration, its checkpoints the JSON the checkpoint file holds; and `wow_migrate_imported_key`, one
 * document per key, in the order recorded (its `_id`). A key write runs in a transaction that reads the fences and
 * writes the checkpoints' document, so a write of the checkpoints at once conflicts with it, and one of them is
 * retried.
 */
internal class MongoCheckpointTable(
    private val client: MongoClient,
    databaseName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : CheckpointTable {
    private val database = client.getDatabase(databaseName)
    private val checkpoints: MongoCollection<Document> = database.getCollection(CHECKPOINTS)
    private val keys: MongoCollection<Document> = database.getCollection(KEYS)

    /** Creates the keys' index when missing. */
    fun createSchema() {
        keys.createIndex(Indexes.ascending(CHECKPOINT, PREPARE_KEY, ID), IndexOptions().name(KEYS_INDEX))
    }

    override fun load(name: String): StoredCheckpoints? = checkpoints.find(Filters.eq(ID, name)).first()?.let {
        StoredCheckpoints(
            WowJson.mapper.readValue(it.getString(CHECKPOINTS_FIELD), ImportCheckpoints::class.java),
            it.getLong(VERSION),
            fencesOf(it),
        )
    }

    override fun write(
        name: String,
        version: Long,
        checkpoints: ImportCheckpoints,
        fences: Map<String, Long>,
    ): Boolean {
        val document = Document(ID, name)
            .append(VERSION, version + 1)
            .append(CHECKPOINTS_FIELD, WowJson.mapper.writeValueAsString(checkpoints))
            .append(FENCES, Document(fences))
            .append(UPDATED_AT, clock())
        if (version != 0L) {
            return this.checkpoints.replaceOne(Filters.and(Filters.eq(ID, name), Filters.eq(VERSION, version)), document)
                .matchedCount == 1L
        }
        return try {
            this.checkpoints.insertOne(document)
            true
        } catch (duplicate: MongoWriteException) {
            if (duplicate.error.category != ErrorCategory.DUPLICATE_KEY) throw duplicate
            false
        }
    }

    override fun addKeys(name: String, keys: List<ImportedKey>, admit: (Map<String, Long>) -> Unit) {
        fenced(name, admit) { session ->
            this.keys.insertMany(
                session,
                keys.map { key ->
                    Document(CHECKPOINT, name)
                        .append(PREPARE_KEY, key.name)
                        .append(IMPORTED_KEY, WowJson.mapper.writeValueAsString(key))
                },
            )
        }
    }

    override fun keys(name: String, prepareKey: String, after: String?, limit: Int): List<RecordedKey> {
        val filters = listOfNotNull(
            Filters.eq(CHECKPOINT, name),
            Filters.eq(PREPARE_KEY, prepareKey),
            after?.let { Filters.gt(ID, ObjectId(it)) },
        )
        return keys.find(Filters.and(filters)).sort(Sorts.ascending(ID)).limit(limit).map {
            RecordedKey(
                it.getObjectId(ID).toHexString(),
                WowJson.mapper.readValue(it.getString(IMPORTED_KEY), ImportedKey::class.java),
            )
        }.toList()
    }

    override fun removeKeys(name: String, positions: List<String>, admit: (Map<String, Long>) -> Unit) {
        fenced(name, admit) { session ->
            keys.deleteMany(
                session,
                Filters.and(Filters.eq(CHECKPOINT, name), Filters.`in`(ID, positions.map(::ObjectId)))
            )
        }
    }

    /**
     * Runs [write] in a transaction with [admit] of the fences, which also writes the checkpoints' document (when
     * there is one): a concurrent write of it then conflicts, and the transaction runs again, reading the new fences.
     */
    private fun fenced(name: String, admit: (Map<String, Long>) -> Unit, write: (ClientSession) -> Unit) {
        client.startSession().use { session ->
            session.withTransaction<Unit> {
                val stored = checkpoints.find(session, Filters.eq(ID, name)).first()
                admit(stored?.let(::fencesOf).orEmpty())
                if (stored != null) {
                    checkpoints.updateOne(session, Filters.eq(ID, name), Updates.set(KEYS_AT, clock()))
                }
                write(session)
            }
        }
    }

    private fun fencesOf(document: Document): Map<String, Long> =
        document.get(FENCES, Document::class.java).orEmpty().mapValues { (it.value as Number).toLong() }

    companion object {
        const val CHECKPOINTS = "wow_migrate_checkpoint"
        const val KEYS = "wow_migrate_imported_key"
        private const val KEYS_INDEX = "wow_migrate_imported_key_by_name"
        private const val ID = "_id"
        private const val VERSION = "version"
        private const val CHECKPOINTS_FIELD = "checkpoints"
        private const val FENCES = "fences"
        private const val UPDATED_AT = "updated_at"
        private const val KEYS_AT = "keys_at"
        private const val CHECKPOINT = "checkpoint"
        private const val PREPARE_KEY = "prepare_key"
        private const val IMPORTED_KEY = "imported_key"
    }
}

/** The fences of a PostgreSQL row, a JSON object of epochs by role. */
private fun fencesOf(json: String): Map<String, Long> =
    WowJson.mapper.readValue(json, Map::class.java).entries.associate { (role, epoch) ->
        role as String to (epoch as Number).toLong()
    }
