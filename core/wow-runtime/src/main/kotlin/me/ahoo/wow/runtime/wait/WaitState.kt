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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage

/**
 * Where one wait's chain stands (design 04 §6.1, decision D62): which hop it waits for, for which commands, and what
 * arrived early. Not thread-safe: [Waiting] guards it.
 *
 * The [hops] are split into generations: the root command's, then, after each saga hop, the commands that saga
 * derived. A hop completes when every command of its generation has reached it; a saga hop's results name the next
 * generation (`derived`), and an empty one completes the chain at once. A failure of any command of the current
 * generation fails the wait at once.
 *
 * A result that cannot be applied yet, because its command belongs to a generation not known yet or its hop comes
 * later in the current generation, is buffered and applied again, in arrival order, whenever the chain moves. Results
 * are deduplicated by (hop, command, function).
 */
internal class WaitState(private val hops: List<Hop>, rootCommandId: String) {
    private var index = 0
    private var generation: Set<String> = setOf(rootCommandId)
    private val past = HashSet<String>()
    private val reached = LinkedHashMap<String, CommandResult>()
    private val buffered = ArrayList<CommandResult>()
    private val seen = HashSet<Triple<Hop, String, FunctionInfo>>()
    private var hopCompleted = false

    /** The last hop reached: the last completed hop, or before one, the root command's furthest stage. */
    var lastReached: CommandResult? = null
        private set

    /** The result the wait ended with, once it ended. */
    var outcome: CommandResult? = null
        private set

    /**
     * Applies [result] and whatever it lets the buffered results apply; returns the results of the hops it
     * completed, in order, ending with the final one when the wait ended. Null when [result] is a duplicate or the
     * wait already ended.
     */
    fun offer(result: CommandResult): List<CommandResult>? {
        if (outcome != null || !seen.add(Triple(result.hop, result.commandId, result.function))) {
            return null
        }
        val completed = ArrayList<CommandResult>()
        var moved = apply(result, completed)
        while (moved && outcome == null && buffered.isNotEmpty()) {
            val pending = buffered.toList()
            buffered.clear()
            moved = false
            pending.forEach { moved = apply(it, completed) || moved }
        }
        return completed
    }

    /** Ends the wait with [result], unless it already ended; returns it as the final result. */
    fun finish(result: CommandResult): List<CommandResult>? {
        if (outcome != null) {
            return null
        }
        outcome = result
        return listOf(result)
    }

    /** Applies [result]; true when it moved the chain to a next hop or ended the wait. */
    private fun apply(result: CommandResult, completed: MutableList<CommandResult>): Boolean {
        val id = result.commandId
        if (id in past || outcome != null) {
            return false
        }
        if (id !in generation) {
            buffered += result
            return false
        }
        if (result.status != CommandResult.Status.SUCCEEDED) {
            outcome = result
            completed += result
            return true
        }
        val target = hops[index]
        return when {
            result.hop == target -> {
                reached[id] = result
                if (reached.keys.containsAll(generation)) {
                    complete(completed)
                    true
                } else {
                    false
                }
            }

            laterInGeneration(result.hop) -> {
                buffered += result
                false
            }

            else -> {
                // Progress the plan does not wait for, such as COMMITTED before the snapshot: kept for a timeout.
                if (!hopCompleted && result.hop.stage <= target.stage) {
                    lastReached = result
                }
                false
            }
        }
    }

    private fun laterInGeneration(hop: Hop): Boolean {
        for (next in index + 1 until hops.size) {
            if (isSaga(next - 1)) {
                return false
            }
            if (hops[next] == hop) {
                return true
            }
        }
        return false
    }

    /** A HANDLED hop followed by more hops: a saga's, whose results name the next generation. */
    private fun isSaga(at: Int): Boolean = hops[at].stage == Stage.HANDLED && at < hops.lastIndex

    private fun complete(completed: MutableList<CommandResult>) {
        val results = reached.values.toList()
        val saga = isSaga(index)
        val derived = if (saga) results.flatMapTo(LinkedHashSet()) { it.derived } else emptySet()
        val result = results.last().let { if (saga) it.copy(derived = derived.toList()) else it }
        completed += result
        lastReached = result
        hopCompleted = true
        reached.clear()
        index++
        if (saga) {
            past += generation
            generation = derived
        }
        if (index == hops.size || (saga && derived.isEmpty())) {
            outcome = result
        }
    }
}
