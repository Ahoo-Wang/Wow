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

package me.ahoo.wow.runtime.ledger

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerCommand
import me.ahoo.wow.api.ledger.LedgerResolution
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.RetryParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.ParkedView
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.subscription.SubscriptionId
import tools.jackson.core.JacksonException
import kotlin.time.Duration

/**
 * Runs the ledger's system commands (`wow.ledger.*`) that arrive on a context's command topic, sent by
 * the ops context for an entry of this service: the runtime runs them on the node's [parked] entries, not the kernel.
 *
 * Each is authorized by its source: it is taken only when the ops context's saga derived
 * it, a command with a causation whose reply goes to [opsContext] (`wow.ops.publish.context`, `ops` by default),
 * where that saga takes the rejections of the commands it sends. The operator is not authorized here: the ops service
 * authorized them when they asked, and their roles do not travel with the command. A command from any
 * other source is refused ([FrameworkCodes.AUTH_FORBIDDEN]); that, an unknown ledger command and a body that does not
 * read are rejections, which the intake answers as it answers any, its receipt going back to the sender, so the ops
 * entry records a refusal of its own action.
 *
 * Retry and Skip act at once when this node owns the entry's partition, else leave a request for its owner
 * ([ParkedOps.retry], [ParkedOps.skip]); marking an entry recoverable and applying a retry spec write the ledger.
 * Whatever came of it is published at once, by the node's [LedgerPublisher]: the entry as it now stands, or that it
 * is resolved, also when it was not parked any more, so the ops view of an entry it missed the resolution of catches
 * up.
 */
internal class LedgerCommands(
    private val parked: ParkedOps,
    private val opsContext: String = Ledger.OPS_CONTEXT,
    private val publisher: () -> LedgerPublisher? = { null },
) {
    /** Runs [wire], a ledger command; the error of its rejection, or null when it ran. */
    suspend fun execute(wire: CommandWire): ErrorInfo? {
        val type = Ledger.COMMANDS[wire.type]
            ?: return ErrorInfo(FrameworkCodes.COMMAND_MALFORMED, "${wire.type} is not a ledger command.")
        val command = try {
            // A request is read strictly: an unknown value, such as a recoverable mark, is refused, never taken for
            // `unknown` as a reader older than its writer takes it.
            WowJson.mapper.treeToValue(wire.body, type)
        } catch (malformed: JacksonException) {
            return ErrorInfo(
                FrameworkCodes.COMMAND_MALFORMED,
                "The ${wire.type} ${wire.id} does not read: ${malformed.originalMessage}"
            )
        }
        // The command topic it came through is the subscription's context's (Ledger.targetOf).
        val subscription: SubscriptionId
        val ref = try {
            subscription = SubscriptionId(wire.ref.type.context, command.subscription)
            command.toRef()
        } catch (invalid: IllegalArgumentException) {
            return ErrorInfo(
                FrameworkCodes.COMMAND_MALFORMED,
                "The ${wire.type} ${wire.id} names no aggregate: ${invalid.message}"
            )
        }
        if (wire.causation == null || wire.replyTo?.context != opsContext) {
            logger.warn {
                "Refused the ${wire.type} ${wire.id} on $ref: it comes from ${wire.replyTo?.context ?: "no saga"}, " +
                    "not the $opsContext saga; if the ops service runs as another context, make that the ops " +
                    "publication context."
            }
            return ErrorInfo(
                FrameworkCodes.AUTH_FORBIDDEN,
                "${wire.type} is taken from the $opsContext context's saga alone.",
            )
        }
        // The scans leave the entry to the action, which publishes what came of it.
        val action = Action(subscription, ref, wire.id)
        val publisher = publisher() ?: return run(command, action)
        return publisher.acting(subscription, ref) { run(command, action) }
    }

    /**
     * An operator's action, the command [id], on [subscription]'s entry of [ref]: what its outcome is published
     * under ([LedgerPublisher.parked]'s `answering`).
     */
    private class Action(val subscription: SubscriptionId, val ref: AggregateRef, val id: String)

    private suspend fun run(command: LedgerCommand, action: Action): ErrorInfo? {
        val subscription = action.subscription
        val ref = action.ref
        // The entry, read before an action may resolve it, and the parking it holds: the one an unnamed action acts on.
        val current = parked.get(subscription, ref)
        val held = current?.entry?.failedAt
        when (command) {
            is RetryParked -> {
                val outcome = parked.retry(subscription, ref, command.failedAt)
                if (outcome == ParkedOps.Outcome.STALE) {
                    return stale(command, command.failedAt, current)
                }
                settled(action, outcome, LedgerResolution.RETRIED, command.failedAt ?: held)
            }

            is SkipParked -> {
                val outcome = parked.skip(subscription, ref, command.failedAt)
                if (outcome == ParkedOps.Outcome.STALE) {
                    return stale(command, command.failedAt, current)
                }
                settled(action, outcome, LedgerResolution.SKIPPED, command.failedAt)
            }
            is MarkParkedRecoverable -> {
                val view = parked.markRecoverable(subscription, ref, command.recoverable, command.failedAt)
                return changed(command, command.failedAt, action, current, view)
            }

            is ApplyParkedRetrySpec -> {
                if (current == null) {
                    amended(action, null, command.failedAt)
                    return null
                }
                val spec = try {
                    specOf(command, current.spec)
                } catch (invalid: IllegalArgumentException) {
                    return ErrorInfo(FrameworkCodes.COMMAND_MALFORMED, invalid.message ?: "Not a retry spec.")
                }
                val view = parked.applyRetrySpec(subscription, ref, spec, command.failedAt)
                return changed(command, command.failedAt, action, current, view)
            }
        }
        return null
    }

    /**
     * What came of an operator's change by [command], the [action] on the parking at [failedAt]: published as [view]
     * when it applied; refused as stale when the entry is parked at another time ([ParkedOps.missed]); else published
     * as not parked, dated with that parking, or, unnamed, the one the entry held.
     */
    private suspend fun changed(
        command: LedgerCommand,
        failedAt: Long?,
        action: Action,
        current: ParkedView?,
        view: ParkedView?,
    ): ErrorInfo? {
        if (view == null && parked.missed(action.subscription, action.ref, failedAt) == ParkedOps.Outcome.STALE) {
            return stale(command, failedAt, current)
        }
        amended(action, view, failedAt ?: current?.entry?.failedAt)
        return null
    }

    /**
     * The refusal of a retry or a skip that names a parking ([failedAt]) other than the entry's [current] one
     * ([ParkedOps.Outcome.STALE]): sent for an entry that was resolved and parked again since, as a command delivered
     * again, or an operator's action on what the console showed a while ago, would skip events nobody looked at.
     */
    private fun stale(command: LedgerCommand, failedAt: Long?, current: ParkedView?): ErrorInfo {
        val held = current?.entry?.failedAt
        logger.warn { "Refused the stale ${command.javaClass.simpleName} of ${command.subscription} on ${command.toRef()}." }
        return ErrorInfo(
            FrameworkCodes.LEDGER_STALE,
            "The entry of ${command.subscription} on ${command.toRef()} was parked at $held, not at $failedAt: it " +
                "was resolved and parked again since; read it again before acting on it.",
        )
    }

    /**
     * Publishes what came of a retry or a skip, [action]: a resolution dated with the [parking] it resolved, or found
     * resolved already, so ops resolves that one and not a later one it holds; or the entry as it now stands.
     */
    private suspend fun settled(action: Action, outcome: ParkedOps.Outcome, resolved: String, parking: Long?) {
        logger.info { "Ledger command on ${action.ref} by ${action.subscription}: $outcome." }
        val publisher = publisher() ?: return
        when (outcome) {
            ParkedOps.Outcome.RESOLVED -> publisher.resolved(action.subscription, action.ref, resolved, parking)
            ParkedOps.Outcome.NOT_PARKED ->
                publisher.resolved(action.subscription, action.ref, LedgerResolution.NOT_PARKED, parking)

            ParkedOps.Outcome.PARKED, ParkedOps.Outcome.REQUESTED, ParkedOps.Outcome.STALE ->
                parked.get(action.subscription, action.ref)?.let { publisher.parked(it, action.id) }
        }
    }

    /** Publishes what came of a change, [action]: the entry as [view] has it, or not parked, dated with [parking]. */
    private suspend fun amended(action: Action, view: ParkedView?, parking: Long?) {
        val publisher = publisher() ?: return
        if (view == null) {
            publisher.resolved(action.subscription, action.ref, LedgerResolution.NOT_PARKED, parking)
        } else {
            publisher.parked(view, action.id)
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}

        /** The spec [command] gives, each member it leaves out [current]'s. */
        fun specOf(command: ApplyParkedRetrySpec, current: ParkedRetrySpec): ParkedRetrySpec = ParkedRetrySpec(
            maxRetries = command.maxRetries ?: current.maxRetries,
            initialBackoff = command.initialBackoff?.let { durationOf("initialBackoff", it) } ?: current.initialBackoff,
            claimTtl = command.claimTtl?.let { durationOf("claimTtl", it) } ?: current.claimTtl,
        )

        private fun durationOf(name: String, value: String): Duration =
            requireNotNull(Duration.parseOrNull(value.trim())) { "$name is a duration (PT3M, 3m): '$value'." }
    }
}
