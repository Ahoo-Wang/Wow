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

package me.ahoo.wow.manifest

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire

/**
 * A subscription: one handler of a processor, named `processor.function`.
 *
 * - [derives] names the commands a saga's handler may derive, any of its kinds: those its `@Sends(derives)`
 *   declares, else those its return type names (`CommandRequest<X>`, a list of them), empty when it returns `Unit`;
 *   null for a projection or a reactor. A wait chain may follow the saga's hop only into the contexts of these
 *   commands.
 * - [rejected] is set on an `@OnRejected` handler of a saga and on no other: the commands whose
 *   rejections it handles, the one its receipt's `C` names, or empty for any command the saga sends. Such a
 *   handler reads rejections, not state events: its [source] is empty and not read.
 * - [committed] is set on an `@OnCommitted` handler of a saga and on no other: the commands whose
 *   success receipts it handles, the one its receipt's `C` names, or empty for any command the saga sends.
 *   Like an `@OnRejected` handler, it reads receipts ([isReceiptHandler]), not state events. A handler of both
 *   receipts, annotated both, has both set.
 * - [start] is where a new subscription starts in the log: what `@OnEvent(start)` declares,
 *   else [SubscriptionStart.defaultFor] its kind and source; the host may configure another. Null on an `@OnRejected`
 *   or `@OnCommitted` handler, which reads no log.
 * - [schedules] names the commands a saga's handler may schedule, each by its full type name, as its
 *   `@Sends(schedules)` declares; null when it declares none, and always for a projection or a reactor.
 * - [retry] is the handler's own in-process retry budget, as its `@Retry` and its processor's declare it:
 *   null when neither does. The host's `wow.subscriptions.[<context>.<name>].retry.*` overrides it, setting by setting.
 * - [job] is the job of a saga's `@OnSchedule` handler: its subscription reads the context's built-in
 *   job aggregate `<context>.schedule` ([source]) and hands its job's ticks to the handler. Null for an `@OnEvent`
 *   handler.
 * - [calls] names the commands a saga's handler sends to another context with `SagaContext.call`,
 *   as its `@Sends(calls)` declares, each referred to as [derives] refers to a command; null when it
 *   declares none. A wait chain may follow the saga's hop into their contexts too, but they need no command topic:
 *   the node calls their gateways, at the addresses its host configures.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class SubscriptionManifest @InternalWowApi constructor(
    val name: String,
    val kind: SubscriptionKind,
    val source: SubscriptionSource,
    val function: FunctionManifest,
    val derives: List<String>? = null,
    val rejected: List<String>? = null,
    val start: SubscriptionStart? = null,
    val schedules: List<String>? = null,
    val retry: RetryManifest? = null,
    val committed: List<String>? = null,
    val job: JobManifest? = null,
    val calls: List<String>? = null,
) {
    /** Whether it is a saga's `@OnRejected` or `@OnCommitted` handler: it reads receipts, not the log. */
    val isReceiptHandler: Boolean get() = rejected != null || committed != null

    override fun equals(other: Any?): Boolean =
        this === other || other is SubscriptionManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SubscriptionManifest(name=$name, kind=$kind, source=$source, function=$function, derives=$derives, " +
            "rejected=$rejected, start=$start, schedules=$schedules, retry=$retry, committed=$committed, job=$job, calls=$calls)"

    private fun valueFields(): List<Any?> = listOf(
        name,
        kind,
        source,
        function,
        derives,
        rejected,
        start,
        schedules,
        retry,
        committed,
        job,
        calls,
    )
}

/**
 * A job of an `@OnSchedule` handler: its [name], its default rule ([cron] read in [timeZone], missed slots
 * handled as [misfire] says) and its [scope].
 * The host configures the job under `wow.schedules.<name>.*`: its rule, and the retries of its subscriptions.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class JobManifest @InternalWowApi constructor(
    val name: String,
    val cron: String,
    val timeZone: String = "UTC",
    val scope: JobScope = JobScope.SYSTEM,
    val misfire: Misfire = Misfire.COALESCE,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is JobManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "JobManifest(name=$name, cron=$cron, timeZone=$timeZone, scope=$scope, misfire=$misfire)"

    private fun valueFields(): List<Any?> = listOf(name, cron, timeZone, scope, misfire)
}

/**
 * A subscription handler's in-process retry budget as `@Retry` declares it: each setting null when it
 * is not given. Durations are ISO-8601 (`PT2S`). [recoverable], [unrecoverable] and [systemic] are the qualified
 * names of the exception classes the handler classifies itself (`@Retry(recoverable, unrecoverable, systemic)`).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class RetryManifest @InternalWowApi constructor(
    val maxAttempts: Int? = null,
    val initialBackoff: String? = null,
    val maxBackoff: String? = null,
    val timeout: String? = null,
    val recoverable: List<String>? = null,
    val unrecoverable: List<String>? = null,
    /** How many times longer each wait between attempts is than the one before. */
    val multiplier: Double? = null,
    val systemic: List<String>? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RetryManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "RetryManifest(maxAttempts=$maxAttempts, initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, " +
            "timeout=$timeout, recoverable=$recoverable, unrecoverable=$unrecoverable, multiplier=$multiplier, " +
            "systemic=$systemic)"

    private fun valueFields(): List<Any?> =
        listOf(maxAttempts, initialBackoff, maxBackoff, timeout, recoverable, unrecoverable, multiplier, systemic)
}

/**
 * The kind of a subscription a context declares: a projection, a reactor or a saga.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
enum class SubscriptionKind { PROJECTION, REACTOR, SAGA }

/**
 * Where a new subscription, one without a checkpoint, starts reading the log or the stream.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
enum class SubscriptionStart {
    /** From the first entry: a projection builds its read model from the whole history. */
    BEGINNING,

    /** From the entries committed after it starts: a reactor or a saga does not act on the history again. */
    END,

    /**
     * Where its service joins another context's stream: from the [BEGINNING] when no other subscription of the
     * service reads the stream yet, so a service that joins later still gets what was published before, else from
     * the [END], so a handler added later does not act on it again. A reactor's or a saga's handler of another
     * context's stream starts so.
     */
    JOIN,
    ;

    companion object {
        /**
         * Where a handler of [kind] starts when it declares nothing: a projection from the [BEGINNING]; a reactor or a
         * saga from the [END] of its own context's log, and where its service [JOIN]s another context's stream when it
         * reads one ([peer]).
         */
        fun defaultFor(kind: SubscriptionKind, peer: Boolean = false): SubscriptionStart = when {
            kind == SubscriptionKind.PROJECTION -> BEGINNING
            peer -> JOIN
            else -> END
        }
    }
}

/**
 * What a subscription reads: the aggregates (by name) of [context] whose state events it receives, and the event
 * names it is limited to. Empty [aggregates] or [events] mean no limit; a null [context] means the handler's state
 * type is not an aggregate of the module that declares it.
 *
 * [context] may be another context than the subscription's (`@OnEvent(aggregates = ["ctx.agg"])`):
 * the subscription then reads that context's published state events ([PublishedManifest]) instead of the log.
 *
 * [nullableState] says the handler takes a null state, `StateEvent<S?>` or `StateEvent<*>`: only such
 * a handler may read a stream published without the state (`PublishedManifest.includeState = false`).
 *
 * [eventSchemas] is a fragment's alone: the schemas of another context's events a handler takes by type
 * (`StateTypedEvent<V, E>`) without naming their aggregate. The service's Manifest finds the context and the aggregates
 * from the peer contracts on its class path ([PeerSources]) and holds those instead.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class SubscriptionSource @InternalWowApi constructor(
    val context: String? = null,
    val aggregates: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val nullableState: Boolean = false,
    val eventSchemas: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is SubscriptionSource && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SubscriptionSource(context=$context, aggregates=$aggregates, events=$events, nullableState=$nullableState, " +
            "eventSchemas=$eventSchemas)"

    private fun valueFields(): List<Any?> = listOf(context, aggregates, events, nullableState, eventSchemas)
}

/**
 * A stream of state events a context publishes for other contexts: those of its [aggregate], on
 * the Kafka [topic] ([defaultTopic] unless overridden), each with the aggregate's state after the commit unless
 * [includeState] is false, for an aggregate whose state is too large (a subscriber of such a stream
 * declares a nullable state).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class PublishedManifest @InternalWowApi constructor(
    val aggregate: String,
    val topic: String,
    val includeState: Boolean = true,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is PublishedManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "PublishedManifest(aggregate=$aggregate, topic=$topic, includeState=$includeState)"

    private fun valueFields(): List<Any?> = listOf(aggregate, topic, includeState)

    companion object {
        /** The topic of the state events of [aggregate] of [context]: `wow.<context>.<aggregate>.state`. */
        fun defaultTopic(context: String, aggregate: String): String = "wow.$context.$aggregate.state"
    }
}

/**
 * The function a subscription runs, as `CommandResult.function` names it: one a context declares, of one of [KINDS],
 * never a built-in subscription's.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class FunctionManifest @InternalWowApi constructor(
    val kind: FunctionKind,
    val context: String,
    val processor: String,
    val name: String,
) {
    init {
        require(kind in KINDS) { "A Manifest names the functions a context declares, $KINDS, not $kind." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is FunctionManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "FunctionManifest(kind=$kind, context=$context, processor=$processor, name=$name)"

    private fun valueFields(): List<Any?> = listOf(kind, context, processor, name)

    companion object {
        /** The kinds of the functions a context declares, which a Manifest names; a reader leaves out any other. */
        val KINDS: List<FunctionKind> =
            listOf(FunctionKind.COMMAND, FunctionKind.PROJECT, FunctionKind.REACT, FunctionKind.SAGA)
    }
}
