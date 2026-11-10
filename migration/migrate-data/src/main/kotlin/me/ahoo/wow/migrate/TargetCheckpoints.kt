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

/**
 * A migration's checkpoints as the target keeps them ([CheckpointTable]): the [checkpoints], the [version] each write
 * moves on by one, and the [fences] of the runs that wrote them, the latest epoch of each role
 * ([CheckpointRole.label]).
 */
internal data class StoredCheckpoints(
    val checkpoints: ImportCheckpoints,
    val version: Long,
    val fences: Map<String, Long>,
)

/** A key [ImportedKeys] recorded, at [position] in the order they were recorded. */
internal data class RecordedKey(val position: String, val key: ImportedKey)

/**
 * Where the V10 target keeps the checkpoints of the migrations named `target:<name>` beside the log
 * (`wow_migrate_checkpoint`), and the keys their reservation imports confirmed (`wow_migrate_imported_key`).
 */
internal interface CheckpointTable {
    /** The checkpoints of [name]; null before the first write. */
    fun load(name: String): StoredCheckpoints?

    /**
     * Writes [checkpoints] and [fences] as those of [name] at [version] + 1, provided they are still at [version] (0:
     * none written yet); whether they were.
     */
    fun write(name: String, version: Long, checkpoints: ImportCheckpoints, fences: Map<String, Long>): Boolean

    /**
     * Records [keys] for [name] once [admit] accepts the fences stored with its checkpoints, as one step: no write of
     * the checkpoints comes between the two.
     */
    fun addKeys(name: String, keys: List<ImportedKey>, admit: (Map<String, Long>) -> Unit)

    /** Up to [limit] keys of the `PrepareKey` [prepareKey] recorded for [name] after [after], in the order recorded. */
    fun keys(name: String, prepareKey: String, after: String?, limit: Int): List<RecordedKey>

    /** Forgets the keys of [name] at [positions] once [admit] accepts the fences, as one step, as [addKeys] records. */
    fun removeKeys(name: String, positions: List<String>, admit: (Map<String, Long>) -> Unit)
}

/**
 * The fence of a run's writes to the checkpoints of [name] kept in the target: the [epochs] of the roles it holds
 * ([CheckpointLock.epochs]). A write [admit]s the fences stored only when none of its roles was written at a later
 * epoch, the epoch of a run that took the lease over since: a run that paused past its lease and does not know it
 * yet fails at its first write, and stops.
 */
internal class CheckpointFence(private val name: String, private val epochs: Map<CheckpointRole, Long>) {
    fun admit(stored: Map<String, Long>) {
        epochs.forEach { (role, epoch) ->
            val later = stored[role.label] ?: return@forEach
            if (later > epoch) {
                throw MigrationException(
                    "This run lost its ${role.label} lease of the checkpoint target:$name to another run, which has " +
                        "written the checkpoint since (lease epoch $later, this run's $epoch). It stops without " +
                        "writing.",
                )
            }
        }
    }

    /** The fences a write stores: [stored], with this run's epochs. */
    fun merged(stored: Map<String, Long>): Map<String, Long> = stored + epochs.mapKeys { it.key.label }
}

/**
 * The checkpoints of [name] kept in [table]. Each [update] is a compare-and-set on the stored version, retried on a
 * conflict, so runs of different roles, the tail beside a comparison, never lose each other's entries; each write is
 * fenced by [lock]'s epochs ([CheckpointFence]).
 */
internal class TargetCheckpointRepository(
    private val table: CheckpointTable,
    private val name: String,
    private val lock: CheckpointLock,
) : CheckpointRepository {
    private val fence = CheckpointFence(name, lock.epochs)

    override fun load(): ImportCheckpoints = table.load(name)?.checkpoints ?: ImportCheckpoints()

    override fun save(checkpoints: ImportCheckpoints) {
        update { checkpoints }
    }

    override fun update(change: (ImportCheckpoints) -> ImportCheckpoints): ImportCheckpoints {
        while (true) {
            lock.ensureHeld()
            val stored = table.load(name)
            val fences = stored?.fences.orEmpty()
            fence.admit(fences)
            val changed = change(stored?.checkpoints ?: ImportCheckpoints())
            if (table.write(name, stored?.version ?: 0, changed, fence.merged(fences))) {
                return changed
            }
        }
    }
}

/**
 * The keys the reservation import of the checkpoints [checkpoint] confirmed, kept in [table] beside them: each write fenced
 * by [lock]'s epochs as the checkpoints are, read in pages of [pageSize].
 */
internal class TargetImportedKeys(
    private val table: CheckpointTable,
    private val checkpoint: String,
    private val lock: CheckpointLock,
    private val pageSize: Int = PAGE_SIZE,
) : ImportedKeys {
    private val fence = CheckpointFence(checkpoint, lock.epochs)

    override suspend fun add(keys: List<ImportedKey>) {
        if (keys.isEmpty()) {
            return
        }
        blocking {
            lock.ensureHeld()
            table.addKeys(checkpoint, keys, fence::admit)
        }
    }

    override suspend fun forEach(name: String, action: suspend (ImportedKey) -> Unit) {
        pages(name) { page -> page.forEach { action(it.key) } }
    }

    override suspend fun retain(name: String, keep: suspend (ImportedKey) -> Boolean) {
        pages(name) { page ->
            val forgotten = page.filterNot { keep(it.key) }.map { it.position }
            if (forgotten.isNotEmpty()) {
                blocking {
                    lock.ensureHeld()
                    table.removeKeys(checkpoint, forgotten, fence::admit)
                }
            }
        }
    }

    private suspend fun pages(prepareKey: String, action: suspend (List<RecordedKey>) -> Unit) {
        var after: String? = null
        do {
            val page = blocking { table.keys(checkpoint, prepareKey, after, pageSize) }
            action(page)
            after = page.lastOrNull()?.position
        } while (page.size == pageSize)
    }

    private companion object {
        const val PAGE_SIZE = 1_000
    }
}
