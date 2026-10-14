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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerCommand
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.RecordResolved
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.ParkedView
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import tools.jackson.core.JacksonException
import kotlin.time.Duration

/**
 * Runs the ledger's system commands (`wow.ledger.*`, decision D141) that arrive on a context's command topic, sent by
 * the ops context for an entry of this service: the runtime runs them on the node's [parked] entries, not the kernel.
 *
 * Each is authorized by [authorizer] as the operator's routes authorize the same action: the system command of its
 * name on the parked aggregate, without creation, in the command's scope, for the caller its operator was (the
 * operator of the ops command it derives from, decision D163; anonymous when it has none). A denial, an unknown ledger
 * command and a body that does not read are rejections, which the intake answers as it answers any.
 *
 * Resume and Skip act at once when this node owns the entry's partition, else leave a request for its owner
 * ([ParkedOps.resume], [ParkedOps.skip]); marking an entry recoverable and applying a retry spec write the ledger.
 * Whatever came of it is published at once, by the node's [LedgerPublisher]: the entry as it now stands, or that it
 * is resolved, also when it was not parked any more, so the ops view of an entry it missed the resolution of catches
 * up.
 */
internal class LedgerCommands(
    private val parked: ParkedOps,
    private val authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
    private val publisher: () -> LedgerPublisher? = { null },
) {
    /** Runs [wire], a ledger command; the error of its rejection, or null when it ran. */
    suspend fun execute(wire: CommandWire): ErrorInfo? {
        val type = Ledger.COMMANDS[wire.type]
            ?: return ErrorInfo(FrameworkCodes.MALFORMED, "${wire.type} is not a ledger command.")
        val command = try {
            ClusterCodec.mapper.treeToValue(wire.body, type)
        } catch (malformed: JacksonException) {
            return ErrorInfo(
                FrameworkCodes.MALFORMED,
                "The ${wire.type} ${wire.id} does not read: ${malformed.originalMessage}"
            )
        }
        val ref = try {
            command.aggregate.toRef()
        } catch (invalid: IllegalArgumentException) {
            return ErrorInfo(
                FrameworkCodes.MALFORMED,
                "The ${wire.type} ${wire.id} names no aggregate: ${invalid.message}"
            )
        }
        val caller = wire.operator?.let { Caller(it.tenantId, wire.scope, it.id, it.crossScope) }
            ?: Caller(scope = wire.scope)
        val action = CommandAction(CommandType(wire.type, type), ref, wire.scope, Creation.NONE)
        val authorization = authorizer.authorize(caller, action)
        if (authorization is Authorization.Denied) {
            return ErrorInfo(authorization.code, authorization.message)
        }
        return run(command, ref)
    }

    private suspend fun run(command: LedgerCommand, ref: AggregateRef): ErrorInfo? {
        val subscription = command.subscription
        // The context of the entry, read before an action may resolve it.
        val context = parked.get(subscription, ref)?.context
        when (command) {
            is ResumeParked -> settled(
                subscription,
                ref,
                context,
                parked.resume(subscription, ref),
                RecordResolved.RESUMED
            )
            is SkipParked -> settled(subscription, ref, context, parked.skip(subscription, ref), RecordResolved.SKIPPED)
            is MarkParkedRecoverable -> {
                val recoverable = Recoverable.entries.firstOrNull { it.name.equals(command.recoverable, ignoreCase = true) }
                    ?: return ErrorInfo(
                        FrameworkCodes.MALFORMED,
                        "Not a recoverable: '${command.recoverable}'; one of ${Recoverable.entries.joinToString()}.",
                    )
                amended(subscription, ref, context, parked.markRecoverable(subscription, ref, recoverable))
            }

            is ApplyParkedRetrySpec -> {
                val current = parked.get(subscription, ref)
                if (current == null) {
                    amended(subscription, ref, context, null)
                    return null
                }
                val spec = try {
                    specOf(command, current.spec)
                } catch (invalid: IllegalArgumentException) {
                    return ErrorInfo(FrameworkCodes.MALFORMED, invalid.message ?: "Not a retry spec.")
                }
                amended(subscription, ref, context, parked.applyRetrySpec(subscription, ref, spec))
            }
        }
        return null
    }

    private suspend fun settled(
        subscription: String,
        ref: AggregateRef,
        context: String?,
        outcome: ParkedOps.Outcome,
        resolved: String,
    ) {
        logger.info { "Ledger command on $ref by $subscription: $outcome." }
        val publisher = publisher() ?: return
        when (outcome) {
            ParkedOps.Outcome.RESOLVED -> publisher.resolved(subscription, ref, context, resolved)
            ParkedOps.Outcome.NOT_PARKED -> publisher.resolved(subscription, ref, context, RecordResolved.NOT_PARKED)
            ParkedOps.Outcome.PARKED, ParkedOps.Outcome.REQUESTED ->
                parked.get(subscription, ref)?.let { publisher.parked(it) }
        }
    }

    private suspend fun amended(subscription: String, ref: AggregateRef, context: String?, view: ParkedView?) {
        val publisher = publisher() ?: return
        if (view == null) {
            publisher.resolved(subscription, ref, context, RecordResolved.NOT_PARKED)
        } else {
            publisher.parked(view)
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}

        /** The spec [command] gives, each member it leaves out [current]'s. */
        fun specOf(command: ApplyParkedRetrySpec, current: ParkedRetrySpec): ParkedRetrySpec = ParkedRetrySpec(
            maxRetries = command.maxRetries ?: current.maxRetries,
            minBackoff = command.minBackoff?.let { durationOf("minBackoff", it) } ?: current.minBackoff,
            executionTimeout = command.executionTimeout?.let { durationOf("executionTimeout", it) }
                ?: current.executionTimeout,
        )

        private fun durationOf(name: String, value: String): Duration =
            requireNotNull(Duration.parseOrNull(value.trim())) { "$name is a duration (PT3M, 3m): '$value'." }
    }
}
