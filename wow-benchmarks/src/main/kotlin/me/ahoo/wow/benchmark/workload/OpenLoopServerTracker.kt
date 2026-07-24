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

package me.ahoo.wow.benchmark.workload

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * Tracks benchmark-side server work from command-bus subscription until the
 * corresponding command handler terminates.
 *
 * A command handler can terminate before the command-bus `Mono` reports
 * successful completion. The ticket state machine therefore accepts both
 * orders and closes a ticket exactly once only after both observations exist.
 */
class OpenLoopServerTracker {
    private val active = ConcurrentHashMap<String, OpenLoopServerTicket>()
    private val currentOutstanding = AtomicInteger()
    private val peakOutstanding = AtomicInteger()
    private val created = LongAdder()
    private val sendSucceeded = LongAdder()
    private val sendFailed = LongAdder()
    private val sendCancelled = LongAdder()
    private val handlerStarted = LongAdder()
    private val handlerTerminated = LongAdder()
    private val handlerFailed = LongAdder()
    private val handlerCompleted = LongAdder()
    private val handlerErrored = LongAdder()
    private val handlerCancelled = LongAdder()
    private val naturallyClosed = LongAdder()
    private val forcedClosed = LongAdder()
    private val missingHandlerTicket = LongAdder()
    private val duplicateTransitions = LongAdder()
    private val lateTransitions = LongAdder()
    private val handlerTerminalSignals = ConcurrentHashMap<String, LongAdder>()

    fun sendStarted(commandId: String): OpenLoopServerTicket {
        val ticket = OpenLoopServerTicket(commandId, this)
        check(active.putIfAbsent(commandId, ticket) == null) {
            "Duplicate server ticket for command ID: $commandId"
        }
        created.increment()
        val outstanding = currentOutstanding.incrementAndGet()
        peakOutstanding.accumulateAndGet(outstanding, ::maxOf)
        return ticket
    }

    fun handlerStarted(commandId: String): OpenLoopServerTicket? {
        val ticket = active[commandId]
        if (ticket == null) {
            missingHandlerTicket.increment()
            return null
        }
        ticket.recordHandlerStarted()
        return ticket
    }

    fun currentOutstanding(): Int = currentOutstanding.get()

    fun activeTicketCount(): Int = active.size

    fun forceCloseRemaining(): Int {
        var closed = 0
        active.values.forEach { ticket ->
            if (ticket.forceClose()) {
                closed++
            }
        }
        return closed
    }

    fun snapshot(): OpenLoopServerSnapshot =
        OpenLoopServerSnapshot(
            created = created.sum(),
            sendSucceeded = sendSucceeded.sum(),
            sendFailed = sendFailed.sum(),
            sendCancelled = sendCancelled.sum(),
            handlerStarted = handlerStarted.sum(),
            handlerTerminated = handlerTerminated.sum(),
            handlerFailed = handlerFailed.sum(),
            handlerCompleted = handlerCompleted.sum(),
            handlerErrored = handlerErrored.sum(),
            handlerCancelled = handlerCancelled.sum(),
            naturallyClosed = naturallyClosed.sum(),
            forcedClosed = forcedClosed.sum(),
            currentOutstanding = currentOutstanding(),
            peakOutstanding = peakOutstanding.get(),
            activeTickets = activeTicketCount(),
            missingHandlerTicket = missingHandlerTicket.sum(),
            duplicateTransitions = duplicateTransitions.sum(),
            lateTransitions = lateTransitions.sum(),
            handlerTerminalSignals = handlerTerminalSignals.entries
                .sortedBy { it.key }
                .associate { (signal, count) -> signal to count.sum() },
        )

    internal fun recordSendOutcome(outcome: Int) {
        when (outcome) {
            OpenLoopServerTicket.SEND_SUCCEEDED -> sendSucceeded.increment()
            OpenLoopServerTicket.SEND_FAILED -> sendFailed.increment()
            OpenLoopServerTicket.SEND_CANCELLED -> sendCancelled.increment()
            else -> error("Unsupported send outcome flag: $outcome")
        }
    }

    internal fun recordHandlerStarted() {
        handlerStarted.increment()
    }

    internal fun recordHandlerTerminated(
        signalType: String,
        failed: Boolean,
    ) {
        handlerTerminated.increment()
        if (failed) {
            handlerFailed.increment()
        }
        when (signalType) {
            HANDLER_ON_COMPLETE -> handlerCompleted.increment()
            HANDLER_ON_ERROR -> handlerErrored.increment()
            HANDLER_CANCEL -> handlerCancelled.increment()
        }
        handlerTerminalSignals.computeIfAbsent(signalType) { LongAdder() }.increment()
    }

    internal fun recordDuplicateTransition() {
        duplicateTransitions.increment()
    }

    internal fun recordLateTransition() {
        lateTransitions.increment()
    }

    internal fun close(
        ticket: OpenLoopServerTicket,
        forced: Boolean,
    ) {
        check(active.remove(ticket.commandId, ticket)) {
            "Closed server ticket was not active: ${ticket.commandId}"
        }
        val remaining = currentOutstanding.decrementAndGet()
        check(remaining >= 0) {
            "Server outstanding became negative after ${ticket.commandId}: $remaining"
        }
        if (forced) {
            forcedClosed.increment()
        } else {
            naturallyClosed.increment()
        }
    }

    private companion object {
        const val HANDLER_ON_COMPLETE: String = "ON_COMPLETE"
        const val HANDLER_ON_ERROR: String = "ON_ERROR"
        const val HANDLER_CANCEL: String = "CANCEL"
    }
}

class OpenLoopServerTicket internal constructor(
    val commandId: String,
    private val owner: OpenLoopServerTracker,
) {
    private val state = AtomicInteger(0)

    fun sendSucceeded() {
        recordSendOutcome(SEND_SUCCEEDED)
    }

    fun sendFailed() {
        recordSendOutcome(SEND_FAILED)
    }

    fun sendCancelled() {
        recordSendOutcome(SEND_CANCELLED)
    }

    fun handlerTerminated(
        signalType: String,
        failed: Boolean,
    ) {
        while (true) {
            val current = state.get()
            if (current has CLOSED) {
                owner.recordLateTransition()
                return
            }
            if (current has HANDLER_TERMINATED || current lacks HANDLER_STARTED) {
                owner.recordDuplicateTransition()
                return
            }
            if (state.compareAndSet(current, current or HANDLER_TERMINATED)) {
                owner.recordHandlerTerminated(signalType, failed)
                tryCloseNaturally()
                return
            }
        }
    }

    internal fun recordHandlerStarted() {
        while (true) {
            val current = state.get()
            if (current has CLOSED) {
                owner.recordLateTransition()
                return
            }
            if (current has HANDLER_STARTED) {
                owner.recordDuplicateTransition()
                return
            }
            if (state.compareAndSet(current, current or HANDLER_STARTED)) {
                owner.recordHandlerStarted()
                return
            }
        }
    }

    internal fun forceClose(): Boolean {
        while (true) {
            val current = state.get()
            if (current has CLOSED) {
                return false
            }
            if (state.compareAndSet(current, current or CLOSED)) {
                owner.close(this, forced = true)
                return true
            }
        }
    }

    private fun recordSendOutcome(outcome: Int) {
        while (true) {
            val current = state.get()
            if (current has CLOSED) {
                owner.recordLateTransition()
                return
            }
            if (current and SEND_OUTCOME_MASK != 0) {
                owner.recordDuplicateTransition()
                return
            }
            if (state.compareAndSet(current, current or outcome)) {
                owner.recordSendOutcome(outcome)
                tryCloseNaturally()
                return
            }
        }
    }

    private fun tryCloseNaturally() {
        while (true) {
            val current = state.get()
            if (current has CLOSED) {
                return
            }
            val sendOutcome = current and SEND_OUTCOME_MASK
            val handlerHasTerminated = current has HANDLER_TERMINATED
            val failedBeforeHandler =
                sendOutcome == SEND_FAILED && current lacks HANDLER_STARTED
            if (!handlerHasTerminated && !failedBeforeHandler) {
                return
            }
            if (sendOutcome == 0) {
                return
            }
            if (state.compareAndSet(current, current or CLOSED)) {
                owner.close(this, forced = false)
                return
            }
        }
    }

    private infix fun Int.has(flag: Int): Boolean = this and flag != 0

    private infix fun Int.lacks(flag: Int): Boolean = this and flag == 0

    internal companion object {
        const val SEND_SUCCEEDED: Int = 1
        const val SEND_FAILED: Int = 1 shl 1
        const val SEND_CANCELLED: Int = 1 shl 2
        const val HANDLER_STARTED: Int = 1 shl 3
        const val HANDLER_TERMINATED: Int = 1 shl 4
        const val CLOSED: Int = 1 shl 5
        const val SEND_OUTCOME_MASK: Int =
            SEND_SUCCEEDED or SEND_FAILED or SEND_CANCELLED
    }
}

data class OpenLoopServerSnapshot(
    val created: Long,
    val sendSucceeded: Long,
    val sendFailed: Long,
    val sendCancelled: Long,
    val handlerStarted: Long,
    val handlerTerminated: Long,
    val handlerFailed: Long,
    val handlerCompleted: Long,
    val handlerErrored: Long,
    val handlerCancelled: Long,
    val naturallyClosed: Long,
    val forcedClosed: Long,
    val currentOutstanding: Int,
    val peakOutstanding: Int,
    val activeTickets: Int,
    val missingHandlerTicket: Long,
    val duplicateTransitions: Long,
    val lateTransitions: Long,
    val handlerTerminalSignals: Map<String, Long>,
) {
    fun invariantViolations(): List<String> =
        buildList {
            val sendTerminal = sendSucceeded + sendFailed + sendCancelled
            if (created != sendTerminal) {
                add("created[$created] != sendTerminal[$sendTerminal]")
            }
            if (handlerTerminated != handlerStarted) {
                add("handlerTerminated[$handlerTerminated] != handlerStarted[$handlerStarted]")
            }
            val classifiedHandlerTerminals =
                handlerCompleted + handlerErrored + handlerCancelled
            if (handlerTerminated != classifiedHandlerTerminals) {
                add(
                    "handlerTerminated[$handlerTerminated] != classifiedHandlerTerminals" +
                        "[$classifiedHandlerTerminals]"
                )
            }
            if (handlerCancelled != 0L) {
                add("handlerCancelled[$handlerCancelled] != 0")
            }
            if (created != naturallyClosed + forcedClosed) {
                add(
                    "created[$created] != naturallyClosed + forcedClosed" +
                        "[${naturallyClosed + forcedClosed}]"
                )
            }
            if (currentOutstanding != 0) {
                add("currentOutstanding[$currentOutstanding] != 0")
            }
            if (activeTickets != 0) {
                add("activeTickets[$activeTickets] != 0")
            }
            if (forcedClosed != 0L) {
                add("forcedClosed[$forcedClosed] != 0")
            }
            if (missingHandlerTicket != 0L) {
                add("missingHandlerTicket[$missingHandlerTicket] != 0")
            }
            if (duplicateTransitions != 0L) {
                add("duplicateTransitions[$duplicateTransitions] != 0")
            }
            if (lateTransitions != 0L) {
                add("lateTransitions[$lateTransitions] != 0")
            }
        }
}
