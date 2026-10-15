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
 * generation (`derived`), and an empty one ends the chain at once, short of its plan when hops remain (decision D212):
 * the final result is the saga hop's, marked [CommandResult.chainEnded], with the hops not reached as
 * [CommandResult.pending]. A failure of any command of the current generation fails the wait at once.
 *
 * Each command's result at the hop its generation waits for is emitted as it applies, so a hop of N commands emits N
 * results, the last of them completing it; the stream of a chain shows each derived command as it settles.
 *
 * A result that cannot be applied yet, because its command belongs to a generation not known yet or its hop comes
 * later in the current generation, is buffered and applied again, in arrival order, whenever the chain moves. Results
 * are deduplicated by their [SignalKey] (design 08 §8): the hop, the command, and the source, the context and function
 * that produced the result. A copy from the same source is dropped, whichever node or delivery sent it, while the same
 * hop reported by another context's function is not taken for it.
 *
 * The results a command's decide function put ([me.ahoo.wow.api.decide.DecideScope.putResult], decision D49) are
 * answered with its commit only, at COMMITTED, and are not persisted, so a subscription that handles the commit
 * cannot know them. The state keeps each command's from its result at COMMITTED, whenever that arrives, even after
 * the wait ended, and [carry] puts them on its results at the later hops: the default wait, which ends at the
 * snapshot, answers them too. A duplicate request was not decided again, so it has none to carry.
 */
internal class WaitState(private val hops: List<Hop>, rootCommandId: String) {
    private var index = 0
    private var generation: Set<String> = setOf(rootCommandId)
    private val past = HashSet<String>()
    private val reached = LinkedHashMap<String, CommandResult>()
    private val buffered = ArrayList<CommandResult>()
    private val seen = HashSet<SignalKey>()
    private val committedResults = HashMap<String, Map<String, Any>>()
    private var hopCompleted = false

    /** The last hop reached: the last completed hop, or before one, the root command's furthest stage. */
    var lastReached: CommandResult? = null
        private set

    /** The result the wait ended with, once it ended. */
    var outcome: CommandResult? = null
        private set

    /**
     * Applies [result] and whatever it lets the buffered results apply; returns the results that reached the hop
     * their generation waits for, one per command, in order, ending with the final one when the wait ended. Null
     * when [result] is a duplicate or the wait already ended.
     */
    fun offer(result: CommandResult): List<CommandResult>? {
        if (result.hop.stage == Stage.COMMITTED && result.results.isNotEmpty()) {
            committedResults.putIfAbsent(result.commandId, result.results)
        }
        if (outcome != null || !seen.add(SignalKey.of(result))) {
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

    /**
     * [result] with the results its command answered at COMMITTED, when it is a result at a later hop that has none
     * of its own; [result] itself otherwise.
     */
    fun carry(result: CommandResult): CommandResult {
        if (result.hop.stage != Stage.HANDLED || result.results.isNotEmpty()) {
            return result
        }
        return committedResults[result.commandId]?.let { result.copyWith(results = it) } ?: result
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
                if (reached.put(id, result) == null) {
                    completed += result
                }
                if (reached.keys.containsAll(generation)) {
                    // A hop completes only with the first result of its last command, emitted just now: a chain
                    // that ended early ends with that result marked as its end, not with one more result (D212).
                    complete()?.let { ended -> completed[completed.lastIndex] = ended }
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

    /**
     * Completes the current hop, whose results were already emitted one per command: the next generation is what the
     * saga hop's results derived together, and the hop as a whole, with all of them, is what a timeout reports.
     *
     * A saga hop that derived nothing, with hops still to come, ends the chain early (decision D212): the wait ends at
     * this hop, with its own status, its result marked [CommandResult.chainEnded] and naming the hops not reached
     * ([CommandResult.pending]). Returns that result; null when the chain goes on or reached its plan's last hop.
     */
    private fun complete(): CommandResult? {
        val results = reached.values.toList()
        val saga = isSaga(index)
        val derived = if (saga) results.flatMapTo(LinkedHashSet()) { it.derived } else emptySet()
        val result = results.last().let { if (saga) it.copyWith(derived = derived.toList()) else it }
        lastReached = result
        hopCompleted = true
        reached.clear()
        index++
        if (saga) {
            past += generation
            generation = derived
        }
        if (index == hops.size) {
            outcome = result
        } else if (saga && derived.isEmpty()) {
            return result.copyWith(chainEnded = true, pending = hops.subList(index, hops.size).toList()).also {
                outcome = it
            }
        }
        return null
    }
}

/**
 * What identifies a signal of a wait (design 08 §8, the wait itself aside): the [hop] reached, by which [commandId], and
 * its [source], the function that produced the result, of the context that signalled ([FunctionInfo.context]): a
 * subscription, or the decide function of a command that another context executed.
 */
internal data class SignalKey(val hop: Hop, val commandId: String, val source: FunctionInfo) {
    companion object {
        fun of(result: CommandResult): SignalKey = SignalKey(result.hop, result.commandId, result.function)
    }
}
