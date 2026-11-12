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

package me.ahoo.wow.api.ledger

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.naming.Escapes

/**
 * The failure ledger across services: each service keeps its own ledger, atomic with its
 * checkpoints, and publishes its changes to the ops context, which aggregates them; an operator's action goes back to
 * the owning service.
 *
 * - **Publishing**: a service sends each change of an entry as a command to the aggregate [PARKED] of the ops context
 *   ([OPS_CONTEXT] by default), through that context's command topic: [RECORD_PARKED] with the [LedgerEntry] as its
 *   member [ENTRY] while the entry is parked, [RECORD_RESOLVED] with the [LedgerResolution] as its member [RESOLUTION]
 *   once it is not. The aggregate's id is [parkedId]: one per service, subscription and parked aggregate.
 * - **Acting**: the ops context sends the system commands [RETRY_PARKED], [SKIP_PARKED],
 *   [MARK_PARKED_RECOVERABLE] and [APPLY_PARKED_RETRY_SPEC] ([RetryParked], [SkipParked], [MarkParkedRecoverable],
 *   [ApplyParkedRetrySpec]) to the command topic of the context that holds the entry ([LedgerEntry.context]),
 *   addressed to [targetOf] that context and the entry's ops id. The owning service authorizes each as the system
 *   command of that name on the parked aggregate, as it does the same action on its own operator routes, and runs it.
 *
 * Their names sit in the system namespace [NAMESPACE], as the lifecycle commands' do.
 */
object Ledger {
    /** The namespace of the ledger's system commands. */
    const val NAMESPACE: String = "wow.ledger"

    const val RETRY_PARKED: String = "$NAMESPACE.RetryParked"
    const val SKIP_PARKED: String = "$NAMESPACE.SkipParked"
    const val MARK_PARKED_RECOVERABLE: String = "$NAMESPACE.MarkParkedRecoverable"
    const val APPLY_PARKED_RETRY_SPEC: String = "$NAMESPACE.ApplyParkedRetrySpec"

    /** The context a service publishes its ledger to unless it is configured otherwise. */
    const val OPS_CONTEXT: String = "ops"

    /** The aggregate of the ops context that records one parked entry of one service. */
    const val PARKED: String = "parked"

    /** The simple names of the commands a service sends to [PARKED]: their full names are `<ops>.parked.<name>`. */
    const val RECORD_PARKED: String = "RecordParked"
    const val RECORD_RESOLVED: String = "RecordResolved"

    /** The member of [RECORD_PARKED]'s body that holds its [LedgerEntry]. */
    const val ENTRY: String = "entry"

    /** The member of [RECORD_RESOLVED]'s body that holds its [LedgerResolution]. */
    const val RESOLUTION: String = "resolution"

    /** The ledger's system commands, by their names. */
    @JvmField
    val COMMANDS: Map<String, Class<out LedgerCommand>> = mapOf(
        RETRY_PARKED to RetryParked::class.java,
        SKIP_PARKED to SkipParked::class.java,
        MARK_PARKED_RECOVERABLE to MarkParkedRecoverable::class.java,
        APPLY_PARKED_RETRY_SPEC to ApplyParkedRetrySpec::class.java,
    )

    private val NAMES: Map<Class<*>, String> = COMMANDS.entries.associate { (name, type) -> type to name }

    /** The system name of [type], a ledger command, or null when it is none. */
    @JvmStatic
    fun nameOf(type: Class<*>): String? = NAMES[type]

    /**
     * The id of the ops aggregate recording the entry of [ref] that [subscription], of [context], parked in [service]:
     * `<service>:<context>:<subscription>:<aggregate context>.<aggregate>:<tenant>:<id>`, so one service's entry of one
     * aggregate is one ops aggregate, whichever node publishes it, and two contexts' subscriptions of one name keep
     * entries of their own. Each field has its `%` and `:` percent-escaped (`%25`, `%3A`, [Escapes]), and the aggregate
     * type's context its `.` too ([Escapes.typeText]), so two entries never share an id however their names join:
     * tenant `a:b` with id `c` and tenant `a` with id `b:c` stay apart, as do context `a.b`'s aggregate `c` and context
     * `a`'s `b.c`.
     */
    @JvmStatic
    fun parkedId(service: String, context: String, subscription: String, ref: AggregateRef): String {
        require(service.isNotBlank()) { "A ledger entry names its service." }
        require(context.isNotBlank()) { "A ledger entry names its subscription's context." }
        require(subscription.isNotBlank()) { "A ledger entry names its subscription." }
        return listOf(
            Escapes.escaped(service, RESERVED),
            Escapes.escaped(context, RESERVED),
            Escapes.escaped(subscription, RESERVED),
            Escapes.typeText(ref.type, RESERVED),
            Escapes.escaped(ref.tenantId, RESERVED),
            Escapes.escaped(ref.id, RESERVED),
        ).joinToString(":")
    }

    /** The characters a field of a parked entry's ops id escapes: its separator and `%`. */
    private const val RESERVED = "%:"

    /**
     * Where a system command of the ledger on the entry [parkedId] names ([Ledger.parkedId]) is sent: the command
     * topic of [context], the context that holds the entry, keyed by the entry rather than by the parked aggregate, so
     * a command on an entry of the context's own intake is not held behind the very aggregate it parked, and the
     * commands of one entry keep their order.
     */
    @JvmStatic
    fun targetOf(context: String, parkedId: String): AggregateRef =
        AggregateType(context, NAMESPACE).ref(Caller.DEFAULT_TENANT, parkedId)
}

/**
 * A system command of the ledger: an operator's action on [subscription]'s entry of the parked
 * aggregate [id] of [tenantId], of the aggregate type [aggregate] of [context]. Its fields are plain values, so any
 * operations tool, not only Wow's own, writes its JSON: `{"subscription": ..., "context": ..., "aggregate": ...,
 * "tenantId": ..., "id": ...}`. A caller sends it as the system command its name says ([Ledger.COMMANDS]), which a
 * service's call permits must list explicitly (`wow.ledger.*`).
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
sealed interface LedgerCommand {
    val subscription: String
    val context: String
    val aggregate: String
    val tenantId: String
    val id: String

    /** The parked aggregate this names. */
    fun toRef(): AggregateRef = AggregateType(context, aggregate).ref(tenantId, id)
}

/**
 * Handles the parked aggregate again from where it is parked ([Ledger.RETRY_PARKED]).
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class RetryParked @InternalWowApi constructor(
    override val subscription: String,
    override val context: String,
    override val aggregate: String,
    override val tenantId: String,
    override val id: String,
    /**
     * When the entry acted on was parked (epoch milliseconds, the entry's `failedAt`), as the caller saw it: the owning
     * service refuses the action as [me.ahoo.wow.api.result.FrameworkCodes.LEDGER_STALE] when the entry it holds was
     * parked at another time, resolved and parked again since. Null acts on the entry it holds, whatever its parking: a
     * retry only handles the parked streams again, so acting on the current parking loses nothing.
     */
    val failedAt: Long? = null,
) : LedgerCommand {
    override fun equals(other: Any?): Boolean =
        this === other || other is RetryParked && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "RetryParked(subscription=$subscription, context=$context, aggregate=$aggregate, tenantId=$tenantId, id=$id, " +
            "failedAt=$failedAt)"

    private fun valueFields(): List<Any?> = listOf(subscription, context, aggregate, tenantId, id, failedAt)

    companion object {
        /** A RetryParked of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            subscription: String,
            context: String,
            aggregate: String,
            tenantId: String,
            id: String,
            failedAt: Long? = null,
        ): RetryParked = RetryParked(subscription, context, aggregate, tenantId, id, failedAt)

        /** A RetryParked of [subscription]'s entry of [ref], of the parking at [failedAt] when given. */
        @JvmStatic
        @JvmOverloads
        fun of(
            subscription: String,
            ref: AggregateRef,
            failedAt: Long? = null,
        ): RetryParked =
            RetryParked(subscription, ref.type.context, ref.type.name, ref.tenantId, ref.id, failedAt)
    }
}

/**
 * Resolves the entry without handling what was parked ([Ledger.SKIP_PARKED]).
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class SkipParked @InternalWowApi constructor(
    override val subscription: String,
    override val context: String,
    override val aggregate: String,
    override val tenantId: String,
    override val id: String,
    /**
     * When the entry skipped was parked (epoch milliseconds, the entry's `failedAt`), as the caller saw it: the owning
     * service refuses the skip as [me.ahoo.wow.api.result.FrameworkCodes.LEDGER_STALE] when the entry it holds was
     * parked at another time, resolved and parked again since. Required: a skip discards what was parked, so it names
     * the parking it discards, never one nobody looked at.
     */
    val failedAt: Long,
) : LedgerCommand {
    override fun equals(other: Any?): Boolean =
        this === other || other is SkipParked && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SkipParked(subscription=$subscription, context=$context, aggregate=$aggregate, tenantId=$tenantId, id=$id, " +
            "failedAt=$failedAt)"

    private fun valueFields(): List<Any?> = listOf(subscription, context, aggregate, tenantId, id, failedAt)

    companion object {
        /** A SkipParked of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(
            subscription: String,
            context: String,
            aggregate: String,
            tenantId: String,
            id: String,
            failedAt: Long,
        ): SkipParked = SkipParked(subscription, context, aggregate, tenantId, id, failedAt)

        /** A SkipParked of [subscription]'s entry of [ref], of the parking at [failedAt]. */
        @JvmStatic
        fun of(
            subscription: String,
            ref: AggregateRef,
            failedAt: Long,
        ): SkipParked =
            SkipParked(subscription, ref.type.context, ref.type.name, ref.tenantId, ref.id, failedAt)
    }
}

/**
 * Marks the entry [recoverable] ([Ledger.MARK_PARKED_RECOVERABLE], 9.x `MarkRecoverable`). On the wire the value is
 * spelled `recoverable`, `unrecoverable` or `unknown` ([me.ahoo.wow.api.wire.WireNames]); any other spelling is
 * refused.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class MarkParkedRecoverable @InternalWowApi constructor(
    override val subscription: String,
    override val context: String,
    override val aggregate: String,
    override val tenantId: String,
    override val id: String,
    val recoverable: Recoverable,
    /**
     * When the entry acted on was parked (epoch milliseconds, the entry's `failedAt`), as the caller saw it: the owning
     * service refuses the action as [me.ahoo.wow.api.result.FrameworkCodes.LEDGER_STALE] when the entry it holds was
     * parked at another time, and the check is made in the ledger's own write. Null acts on whichever parking the entry
     * holds: a caller that leaves it out accepts acting on a parking it may not have seen; the ops console always sends
     * it.
     */
    val failedAt: Long? = null,
) : LedgerCommand {
    override fun equals(other: Any?): Boolean =
        this === other || other is MarkParkedRecoverable && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "MarkParkedRecoverable(subscription=$subscription, context=$context, aggregate=$aggregate, tenantId=$tenantId, id=$id, recoverable=$recoverable, failedAt=$failedAt)"

    private fun valueFields(): List<Any?> =
        listOf(subscription, context, aggregate, tenantId, id, recoverable, failedAt)

    companion object {
        /** A MarkParkedRecoverable of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            subscription: String,
            context: String,
            aggregate: String,
            tenantId: String,
            id: String,
            recoverable: Recoverable,
            failedAt: Long? = null,
        ): MarkParkedRecoverable =
            MarkParkedRecoverable(subscription, context, aggregate, tenantId, id, recoverable, failedAt)

        /** A MarkParkedRecoverable of [subscription]'s entry of [ref], parked at [failedAt], [recoverable]. */
        @JvmStatic
        @JvmOverloads
        fun of(
            subscription: String,
            ref: AggregateRef,
            recoverable: Recoverable,
            failedAt: Long? = null,
        ): MarkParkedRecoverable = MarkParkedRecoverable(
            subscription,
            ref.type.context,
            ref.type.name,
            ref.tenantId,
            ref.id,
            recoverable,
            failedAt,
        )
    }
}

/**
 * Applies a retry spec to the entry ([Ledger.APPLY_PARKED_RETRY_SPEC], 9.x `ApplyRetrySpec`): at most [maxRetries]
 * automatic retries, the first [initialBackoff] after the failure, each claiming the entry for [claimTtl]
 * (durations). A member left out keeps the value the entry is retried by now.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class ApplyParkedRetrySpec @InternalWowApi constructor(
    override val subscription: String,
    override val context: String,
    override val aggregate: String,
    override val tenantId: String,
    override val id: String,
    val maxRetries: Int? = null,
    val initialBackoff: String? = null,
    val claimTtl: String? = null,
    /**
     * When the entry acted on was parked (epoch milliseconds, the entry's `failedAt`), as the caller saw it: the owning
     * service refuses the action as [me.ahoo.wow.api.result.FrameworkCodes.LEDGER_STALE] when the entry it holds was
     * parked at another time, and the check is made in the ledger's own write. Null acts on whichever parking the entry
     * holds: a caller that leaves it out accepts acting on a parking it may not have seen; the ops console always sends
     * it.
     */
    val failedAt: Long? = null,
) : LedgerCommand {
    override fun equals(other: Any?): Boolean =
        this === other || other is ApplyParkedRetrySpec && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ApplyParkedRetrySpec(subscription=$subscription, context=$context, aggregate=$aggregate, tenantId=$tenantId, id=$id, maxRetries=$maxRetries, initialBackoff=$initialBackoff, claimTtl=$claimTtl, failedAt=$failedAt)"

    private fun valueFields(): List<Any?> = listOf(
        subscription,
        context,
        aggregate,
        tenantId,
        id,
        maxRetries,
        initialBackoff,
        claimTtl,
        failedAt,
    )

    companion object {
        /** An ApplyParkedRetrySpec of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            subscription: String,
            context: String,
            aggregate: String,
            tenantId: String,
            id: String,
            maxRetries: Int? = null,
            initialBackoff: String? = null,
            claimTtl: String? = null,
            failedAt: Long? = null,
        ): ApplyParkedRetrySpec = ApplyParkedRetrySpec(
            subscription,
            context,
            aggregate,
            tenantId,
            id,
            maxRetries,
            initialBackoff,
            claimTtl,
            failedAt,
        )

        /**
         * An ApplyParkedRetrySpec of [subscription]'s entry of [ref], parked at [failedAt], [maxRetries],
         * [initialBackoff], [claimTtl].
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            subscription: String,
            ref: AggregateRef,
            maxRetries: Int? = null,
            initialBackoff: String? = null,
            claimTtl: String? = null,
            failedAt: Long? = null,
        ): ApplyParkedRetrySpec =
            ApplyParkedRetrySpec(
                subscription,
                ref.type.context,
                ref.type.name,
                ref.tenantId,
                ref.id,
                maxRetries,
                initialBackoff,
                claimTtl,
                failedAt,
            )
    }
}

/**
 * What an operator marked a parked entry (9.x `RecoverableType`); only [UNRECOVERABLE] stops its automatic retries.
 *
 * No case is added within 10.x, so a `when` over it may name every case.
 */
enum class Recoverable {
    /** Nobody said: retried automatically. */
    UNKNOWN,

    /** Retried automatically. */
    RECOVERABLE,

    /** Not retried automatically; an operator can still retry or skip it. */
    UNRECOVERABLE,
}

/**
 * An operator's request on a parked entry, left in the ledger by a node that does not own the entry's partition:
 * the owner takes it (the ledger's `ParkedAmendment.TakeRequest`) and runs it.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
enum class LedgerRequest {
    /** Handle the aggregate again from where it is parked. */
    RETRY,

    /** Resolve the entry without handling what was parked. */
    SKIP,
}
