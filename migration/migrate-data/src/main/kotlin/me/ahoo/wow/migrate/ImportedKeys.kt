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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * A key of the 9.x `PrepareKey` [name] that the reservation import confirmed in V10 (finding R2.4-2): [key] in the
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
    /** The scope the key is unique in: its owner's aggregate type (decision D307), tenant and namespace. */
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
 * JSON lines at [path], one key a line. [add] appends and syncs before the keys are confirmed; [retain] writes the
 * kept keys to a sibling file and replaces this one atomically, so a run stopped mid-write leaves the last one whole.
 * A last line cut short by a stop while appending is ignored: its keys were not confirmed yet.
 */
internal class FileImportedKeys(private val path: Path) : ImportedKeys {
    override suspend fun add(keys: List<ImportedKey>) {
        if (keys.isEmpty()) {
            return
        }
        val lines = keys.map { WowJson.mapper.writeValueAsString(it) }
        withContext(Dispatchers.IO) {
            Files.write(
                path,
                lines,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC,
            )
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
                    writer.newLine()
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
        withContext(Dispatchers.IO) { Files.newBufferedReader(path) }.use { reader ->
            var line = withContext(Dispatchers.IO) { reader.readLine() }
            var number = 0
            while (line != null) {
                number++
                val next = withContext(Dispatchers.IO) { reader.readLine() }
                val key = try {
                    WowJson.mapper.readValue(line, ImportedKey::class.java)
                } catch (failure: JacksonException) {
                    if (next != null) {
                        throw MigrationException("Line $number of $path is not an imported key: ${failure.message}")
                    }
                    null
                }
                key?.let { action(it) }
                line = next
            }
        }
    }
}
