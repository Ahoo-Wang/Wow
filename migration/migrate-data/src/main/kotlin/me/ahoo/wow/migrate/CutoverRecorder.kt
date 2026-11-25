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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.ImportCutover
import java.util.concurrent.ConcurrentHashMap

/**
 * Records where each V10 context's log ends the imported history ([ImportCutover]): in each partition, the last
 * stream the import appended, or found an earlier run had appended, as a checkpoint row of the target's [store] beside
 * the log. A row only moves forward; a partition the import wrote nothing to has none.
 *
 * **Where the import ended, never the log's head.** Once the service runs, V10 appends its own commits after the
 * imported history; a pass that runs then (a tail left running, a rehearsal's second import, a final pass that finds
 * nothing new) records no more than what it imported, so it never takes those commits for history 9.x acted on.
 *
 * **Nothing imported past it is lost.** The import tells the recorder each position as it settles a stream
 * ([imported]), and saves them ([record]) before it moves a checkpoint of its own past them, as well as after each
 * pass (`import`, `import --restart`, `import --final`, each round of `tail`). A run stopped at any point has saved
 * every imported stream that its next run does not read again; the ones it does read again, that run finds in the
 * log and records.
 *
 * Once the service runs, its subscriptions see the entries up to the cut-over as imported, and its transport sink
 * publishes them marked so: a saga or a reactor passes over them, its projections and every sink take them.
 */
internal class CutoverRecorder(
    private val contexts: Collection<String>,
    private val store: () -> CheckpointStore,
) {
    /** The last imported stream's sequence the import settled, by context and partition. */
    private val imported = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()

    /** What [record] saved, by context and partition; read and written under [saving]. */
    private val recorded = HashMap<String, MutableMap<Int, Long>>()

    /** One [record] at a time: a caller that finds its positions being saved by another waits until they are. */
    private val saving = Mutex()

    /** Takes note that [context]'s log holds a stream the import imported at [position]. */
    fun imported(context: String, position: LogPosition) {
        imported.computeIfAbsent(context) { ConcurrentHashMap() }.merge(position.partition, position.sequence, ::maxOf)
    }

    /**
     * Saves the cut-over of each partition [imported] moved since the last record, one call of the store per context;
     * returns, per context, the partitions it has recorded so far.
     */
    suspend fun record(): Map<String, Int> = saving.withLock {
        imported.forEach { (context, positions) ->
            val saved = recorded.getOrPut(context, ::HashMap)
            val moved = positions.filter { (partition, sequence) -> sequence > (saved[partition] ?: -1) }
            if (moved.isNotEmpty()) {
                val checkpoints = moved.map { (partition, sequence) ->
                    Checkpoint.of(ImportCutover.SUBSCRIPTION, partition, sequence)
                }
                store().saveAll(ImportCutover.groupOf(context), checkpoints)
                saved.putAll(moved)
            }
        }
        contexts.associateWith { recorded[it]?.size ?: 0 }
    }

    /** The line of each context [record] recorded, as the operator reads it. */
    fun render(recorded: Map<String, Int>): String = recorded.entries.joinToString("\n") { (context, partitions) ->
        if (partitions == 0) {
            "CUTOVER $context: this run imported nothing into the log, so the cut-over stays where it was."
        } else {
            "CUTOVER $context: the imported history ends at the import's last stream in $partitions partitions."
        }
    }

    companion object {
        /** The recorder of the V10 contexts [plan] imports into. */
        fun of(plan: MigrationPlan, connections: MigrationConnections): CutoverRecorder = CutoverRecorder(
            plan.aggregates.values.map { it.target.context }.distinct(),
            connections::checkpointStore,
        )
    }
}
