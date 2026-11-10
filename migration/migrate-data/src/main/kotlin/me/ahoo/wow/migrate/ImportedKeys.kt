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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.reservation.ReservationScope
import tools.jackson.core.JacksonException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * A key of the 9.x `PrepareKey` [name] that the reservation import confirmed in V10: [key] in the
 * scope ([context] and [aggregate], the owner's type, [tenantId], [namespace]) for the instance ([ownerTenantId],
 * [ownerId]) of that type. The V10 reservation store keeps no origin, so the import records what it confirmed here, and a later import
 * releases what 9.x has released or given to another owner since, and nothing V10 took itself.
 */
internal data class ImportedKey(
    val name: String,
    val context: String,
    val tenantId: String,
    val namespace: String,
    val key: String,
    val aggregate: String,
    val ownerTenantId: String,
    val ownerId: String,
) {
    /** The scope the key is unique in: its owner's aggregate type, tenant and namespace. */
    fun scope(): ReservationScope = ReservationScope.of(AggregateType(context, aggregate), tenantId, namespace)

    fun owner(): AggregateRef = AggregateType(context, aggregate).ref(ownerTenantId, ownerId)

    override fun toString(): String = "$name/$key of ${owner()} ($tenantId/$namespace)"

    companion object {
        fun of(name: String, key: String, scope: ReservationScope, owner: AggregateRef): ImportedKey =
            ImportedKey(
                name,
                scope.type.context,
                scope.tenantId,
                scope.namespace,
                key,
                owner.type.name,
                owner.tenantId,
                owner.id
            )
    }
}

/** The keys the reservation import confirmed in V10 ([ImportedKey]), kept beside the checkpoints. */
internal interface ImportedKeys {
    /** Records [keys], before they are confirmed, so a key the import confirms is never unrecorded. */
    suspend fun add(keys: List<ImportedKey>)

    /** Calls [action] with each recorded key of the `PrepareKey` [name], in the order they were recorded. */
    suspend fun forEach(name: String, action: suspend (ImportedKey) -> Unit)

    /** Keeps the recorded keys of [name] for which [keep] is true, and every key of other names. */
    suspend fun retain(name: String, keep: suspend (ImportedKey) -> Boolean)
}

/** In memory: a dry run's, which records nothing it would keep, and the tests'. */
internal class InMemoryImportedKeys : ImportedKeys {
    private val keys = mutableListOf<ImportedKey>()

    val all: List<ImportedKey> get() = synchronized(keys) { keys.toList() }

    override suspend fun add(keys: List<ImportedKey>) {
        synchronized(this.keys) { this.keys += keys }
    }

    override suspend fun forEach(name: String, action: suspend (ImportedKey) -> Unit) {
        all.filter { it.name == name }.forEach { action(it) }
    }

    override suspend fun retain(name: String, keep: suspend (ImportedKey) -> Boolean) {
        val kept = all.filter { it.name != name || keep(it) }
        synchronized(keys) {
            keys.clear()
            keys += kept
        }
    }
}

/**
 * JSON lines at [path], one key a line, each ended by a line break: a line without one is the last, cut short by a run
 * that stopped while appending it, and its keys were never confirmed. [add] first cuts that line off, then appends and
 * syncs before the keys are confirmed; a read passes over it; any other line that is not a key fails the read. [retain]
 * writes the kept keys to a sibling file and replaces this one atomically, so a run stopped mid-write leaves the last
 * one whole.
 */
internal class FileImportedKeys(private val path: Path) : ImportedKeys {
    override suspend fun add(keys: List<ImportedKey>) {
        if (keys.isEmpty()) {
            return
        }
        val lines = ByteBuffer.wrap(
            keys.joinToString("") { WowJson.mapper.writeValueAsString(it) + LINE_BREAK }.toByteArray(Charsets.UTF_8)
        )
        withContext(Dispatchers.IO) {
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC,
            ).use { channel ->
                var at = wholeLength(channel)
                channel.truncate(at)
                while (lines.hasRemaining()) {
                    at += channel.write(lines, at)
                }
            }
        }
    }

    override suspend fun forEach(name: String, action: suspend (ImportedKey) -> Unit) {
        read { key -> if (key.name == name) action(key) }
    }

    override suspend fun retain(name: String, keep: suspend (ImportedKey) -> Boolean) {
        if (!Files.exists(path)) {
            return
        }
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        withContext(Dispatchers.IO) { Files.newBufferedWriter(temporary) }.use { writer ->
            read { key ->
                if (key.name != name || keep(key)) {
                    writer.write(WowJson.mapper.writeValueAsString(key))
                    writer.write(LINE_BREAK)
                }
            }
        }
        withContext(Dispatchers.IO) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private suspend fun read(action: suspend (ImportedKey) -> Unit) {
        if (!Files.exists(path)) {
            return
        }
        val cut = withContext(Dispatchers.IO) {
            FileChannel.open(path, StandardOpenOption.READ).use { wholeLength(it) < it.size() }
        }
        withContext(Dispatchers.IO) { Files.newBufferedReader(path) }.use { reader ->
            var line = withContext(Dispatchers.IO) { reader.readLine() }
            var number = 0
            while (line != null) {
                number++
                val next = withContext(Dispatchers.IO) { reader.readLine() }
                if (next == null && cut) {
                    return
                }
                val key = try {
                    WowJson.mapper.readValue(line, ImportedKey::class.java)
                } catch (failure: JacksonException) {
                    throw MigrationException("Line $number of $path is not an imported key: ${failure.message}")
                }
                action(key)
                line = next
            }
        }
    }

    private companion object {
        const val LINE_BREAK = "\n"
        const val TAIL_CHUNK = 4096
        const val LINE_BREAK_BYTE = '\n'.code.toByte()

        /** The length of [channel]'s whole lines: up to and with its last line break, 0 when it has none. */
        fun wholeLength(channel: FileChannel): Long {
            val chunk = ByteBuffer.allocate(TAIL_CHUNK)
            var end = channel.size()
            while (end > 0) {
                val start = maxOf(0L, end - TAIL_CHUNK)
                chunk.clear().limit((end - start).toInt())
                while (chunk.hasRemaining()) {
                    if (channel.read(chunk, start + chunk.position()) < 0) {
                        break
                    }
                }
                for (index in chunk.position() - 1 downTo 0) {
                    if (chunk.get(index) == LINE_BREAK_BYTE) {
                        return start + index + 1
                    }
                }
                end = start
            }
            return 0
        }
    }
}
