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

package me.ahoo.wow.test

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import tools.jackson.databind.JsonNode
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whether a state is a function of its events alone, as every node that replays the log rebuilds it: each state a
 * commit left is written as JSON when it commits ([record]), and once the JVM's clock has moved on, the state at that
 * version is rebuilt from what the specification was given, its events or its given state, and the streams since
 * ([replay]), and written again ([verify]). A property or a sourcing function that reads a clock finer than a
 * millisecond, or a random source, makes the two differ every time; one that reads a date, or compares with a
 * deadline, only across its boundary, which a detekt rule catches instead. There is nothing to allow: a state that
 * its events do not determine differs between the nodes that rebuild it.
 */
internal class ReplayCheck(private val codec: EventCodec, private val replay: (Int) -> Any) {
    private class Left(val command: String, val version: Int, val state: JsonNode, val writtenAt: Long)

    private val left = mutableListOf<Left>()

    /** Writes the state [outcome] of [command] left, when it committed one. */
    fun record(command: Command<*>, outcome: CommandOutcome) {
        if (outcome !is CommandOutcome.Committed) return
        val name = "${command.body.javaClass.simpleName} (${command.id})"
        left += Left(name, outcome.stream.version, codec.stateTree(outcome.state), System.currentTimeMillis())
    }

    /**
     * Rebuilds each state [record] wrote, once the clock has moved past the time it was written at, and fails, by
     * [located], on the first that differs: never on a coroutine's thread, which a wait of up to a millisecond would
     * hold.
     */
    fun verify(located: (AssertionError, String) -> AssertionError) {
        // The last was written last.
        val written = left.lastOrNull()?.writtenAt ?: return
        val checked = left.toList()
        left.clear()
        awaitTick(written)
        checked.forEach {
            val differences = StructuralDiff.of(it.state, codec.stateTree(replay(it.version)))
            if (differences.isNotEmpty()) {
                throw located(AssertionError(MESSAGE + StructuralDiff.lines(differences) + "\n" + WAY_OUT), it.command)
            }
        }
    }

    internal companion object {
        private val MAX_WAIT = 100.milliseconds

        /**
         * Waits for the JVM's clock ([wallClock], milliseconds) to move past [millis], spinning: under a millisecond,
         * failing once [MAX_WAIT] has passed on [monotonic] (nanoseconds) and the clock has still not moved. The clock
         * is read again when the wait is over, so a spinning thread descheduled past the deadline, on a machine busy
         * with other work, wakes to a clock that moved meanwhile and goes on (#6776).
         */
        fun awaitTick(
            millis: Long,
            wallClock: () -> Long = System::currentTimeMillis,
            monotonic: () -> Long = System::nanoTime,
        ) {
            val deadline = monotonic() + MAX_WAIT.inWholeNanoseconds
            while (wallClock() <= millis) {
                if (monotonic() >= deadline) {
                    check(wallClock() > millis) {
                        "The JVM's clock did not move on in $MAX_WAIT, so a state rebuilt from the log cannot be " +
                            "told apart from one that read the clock."
                    }
                    return
                }
                Thread.onSpinWait()
            }
        }

        private const val MESSAGE = "Rebuilt from its log once the clock had moved on, the state is not the one the " +
            "command left (expected the command's, was the rebuilt one's):"
        private const val WAY_OUT = "A state is a function of its events: take the time from the event, its commit " +
            "or the command's @OccurredAt, not the clock; a random value is made in the command function and carried " +
            "by the event."
    }
}
