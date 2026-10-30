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

import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.ImportCutover

/**
 * Records where each V10 context's log ends the imported history ([ImportCutover]): after a pass that appended to
 * the logs (`import`, `import --restart`, `import --final`, each round of `tail`), the head of every partition of each
 * of [contexts], as a checkpoint row of the target's [store] beside the log. A row only moves forward, so each pass
 * moves it to where the history now ends; a partition the import wrote nothing to has none.
 *
 * Once the service runs, its subscriptions see the entries up to the cut-over as imported, and its transport sink
 * publishes them marked so: a peer's saga or reactor passes over them, its projections and every sink take them.
 */
internal class CutoverRecorder(
    private val contexts: Collection<String>,
    private val logOf: (String) -> EventLog,
    private val store: () -> CheckpointStore,
) {
    /** Records the cut-over of each context at its log's heads; returns, per context, the partitions it holds. */
    suspend fun record(): Map<String, Int> {
        val checkpoints = store()
        return contexts.associateWith { context ->
            val log = logOf(context)
            val group = ImportCutover.groupOf(context)
            (0 until log.partitions).count { partition ->
                val head = log.head(partition) ?: return@count false
                checkpoints.save(group, ImportCutover.SUBSCRIPTION, partition, head)
                true
            }
        }
    }

    /** The line of each context [record] recorded, as the operator reads it. */
    fun render(recorded: Map<String, Int>): String = recorded.entries.joinToString("\n") { (context, partitions) ->
        "CUTOVER $context: the imported history ends at the log's head in $partitions partitions."
    }

    companion object {
        /** The recorder of the V10 contexts [plan] imports into. */
        fun of(plan: MigrationPlan, connections: MigrationConnections): CutoverRecorder = CutoverRecorder(
            plan.aggregates.values.map { it.target.context }.distinct(),
            connections::logOf,
            connections::checkpointStore,
        )
    }
}
