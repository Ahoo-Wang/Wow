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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.log.Partitioner

/**
 * What an operator does with the parked entries of a node's subscriptions: list
 * and read them, resume or skip one, mark one recoverable or not (9.x `MarkRecoverable`), apply a retry spec to one
 * (9.x `ApplyRetrySpec`). The host serves it over HTTP; any node of the service answers for every entry.
 *
 * A subscription is named by its context and name ([SubscriptionId]): two contexts of one service may each have a
 * subscription of one name, such as their `wow.snapshot`. An entry is the subscription's in the ledger of the context
 * (or the topic) it reads. [resume] and [skip] act at once
 * when this node owns the entry's partition; otherwise they leave a request on the entry ([LedgerRequest]), which the
 * owner takes on its next scan of the parked entries, and answer [Outcome.REQUESTED]. Marking an entry
 * and applying a spec write the ledger directly: the owner reads them on its next scan.
 */
@InternalWowApi
class ParkedOps internal constructor(private val scopes: () -> List<Scope>) {
    /** The subscriptions whose entries this node reads, in order: by context, then by name. */
    val subscriptions: List<SubscriptionId> get() = scopes().flatMapTo(sortedSetOf()) { it.ids() }.toList()

    /**
     * The subscriptions whose entries this node reads, each by the log or lease group it runs in, ordered by group and
     * name: a subscription is one by both, since each context has its own `snapshot`.
     */
    val groupedSubscriptions: List<ParkedSubscription>
        get() = scopes().flatMapTo(sortedSetOf()) { scope -> scope.names().map { ParkedSubscription(scope.group, it) } }
            .toList()

    /**
     * How many entries [subscription] has parked, up to [max]: counted by the ledger ([FailureLedger.count]), which
     * reads none of them.
     */
    suspend fun count(subscription: ParkedSubscription, max: Int): Int {
        require(max >= 1) { "At least one parked entry is counted." }
        var count = 0
        for (scope in scopes().filter { it.group == subscription.group && subscription.name in it.names() }) {
            count += scope.ledger.count(subscription.name, max - count)
            if (count >= max) {
                break
            }
        }
        return count
    }

    /**
     * Up to [limit] entries, ordered by subscription ([SubscriptionId]) and then by aggregate as [FailureLedger.list]
     * orders them: [subscription]'s only when given, after [after] (exclusive) when given, and only [tenantId]'s when given (a
     * caller's own tenant, for one that may not act across tenants), which the ledger filters. Each subscription's
     * ledgers are read once, for at most [limit] entries each. [ParkedPage.next] is where the next page starts, null
     * when there is none.
     */
    suspend fun list(
        subscription: SubscriptionId? = null,
        after: ParkedKey? = null,
        limit: Int = DEFAULT_LIMIT,
        tenantId: String? = null,
    ): ParkedPage {
        require(limit in 1..MAX_LIMIT) { "A page holds 1 to $MAX_LIMIT entries." }
        val ids = (subscription?.let(::listOf) ?: subscriptions).filter { after == null || it >= after.subscription }
        val entries = ArrayList<ParkedView>(limit)
        for (id in ids) {
            val refAfter = after?.takeIf { it.subscription == id }?.ref
            entries += page(id, refAfter, limit - entries.size, tenantId)
            if (entries.size >= limit) {
                break
            }
        }
        val next = entries.takeIf { it.size == limit }?.last()?.let { ParkedKey(it.id, it.entry.ref) }
        return ParkedPage(entries, next)
    }

    /**
     * [subscription]'s entries after [after], [tenantId]'s only when given, at most [limit], from every ledger that
     * holds the subscription's.
     */
    private suspend fun page(
        subscription: SubscriptionId,
        after: AggregateRef?,
        limit: Int,
        tenantId: String?
    ): List<ParkedView> {
        val holders = scopes().filter { it.holds(subscription) }
        val byRef = LinkedHashMap<AggregateRef, ParkedView>()
        holders.forEach { scope ->
            scope.ledger.list(subscription.name, after, limit, tenantId)
                .forEach { byRef.putIfAbsent(it.ref, scope.view(it)) }
        }
        return byRef.values.sortedWith(compareBy(REF_ORDER) { it.entry.ref }).take(limit)
    }

    /** The entry parking [ref] for [subscription], if any. */
    suspend fun get(subscription: SubscriptionId, ref: AggregateRef): ParkedView? =
        locate(subscription, ref)?.let { (scope, entry) -> scope.view(entry) }

    /**
     * Handles [subscription]'s parked [ref] again from where it is parked, whatever its retry spec says: at once when
     * this node owns its partition, else by a request to the owner. With [failedAt], only the parking at that time
     * ([ParkedAggregate.failedAt], as the operator saw it): [Outcome.STALE] when the entry was parked at another.
     * Without, the current parking: a resume only handles the parked streams again.
     */
    suspend fun resume(subscription: SubscriptionId, ref: AggregateRef, failedAt: Long? = null): Outcome {
        val (scope, entry) = locate(subscription, ref) ?: return Outcome.NOT_PARKED
        if (failedAt != null && entry.failedAt != failedAt) {
            return Outcome.STALE
        }
        val runtime = scope.runner()?.takeIf { it.acts(subscription.name, ref) }
            ?: return request(scope, subscription, ref, LedgerRequest.RESUME)
        if (runtime.resume(subscription.name, ref)) {
            return Outcome.RESOLVED
        }
        return if (scope.ledger.isParked(subscription.name, ref)) Outcome.PARKED else Outcome.NOT_PARKED
    }

    /**
     * Resolves [subscription]'s parked [ref] without handling what was parked: at once when this node owns its
     * partition and no resume of it runs, else by a request to the owner. Only the parking at [failedAt]
     * ([ParkedAggregate.failedAt], as the operator saw it), which a skip must name since it discards what was parked:
     * [Outcome.STALE] when the entry was parked at another time.
     */
    suspend fun skip(subscription: SubscriptionId, ref: AggregateRef, failedAt: Long): Outcome {
        val (scope, entry) = locate(subscription, ref) ?: return Outcome.NOT_PARKED
        if (entry.failedAt != failedAt) {
            return Outcome.STALE
        }
        val runtime = scope.runner()?.takeIf { it.acts(subscription.name, ref) }
            ?: return request(scope, subscription, ref, LedgerRequest.SKIP)
        if (runtime.skip(subscription.name, ref)) {
            return Outcome.RESOLVED
        }
        // A resume of it runs: the owner (this node) skips it on its next scan, unless the resume resolved it.
        return request(scope, subscription, ref, LedgerRequest.SKIP)
    }

    /**
     * Whether this node runs [subscription]'s partition of [ref] now: it acts on the entry at once, and publishes it.
     * False when the aggregate is not parked by it.
     */
    suspend fun owns(subscription: SubscriptionId, ref: AggregateRef): Boolean {
        val (scope, _) = locate(subscription, ref) ?: return false
        return scope.runner()?.runs(subscription.name, ref) == true
    }

    /** Marks [subscription]'s parked [ref] [recoverable]; null when it is not parked. */
    suspend fun markRecoverable(
        subscription: SubscriptionId,
        ref: AggregateRef,
        recoverable: Recoverable
    ): ParkedView? =
        amend(subscription, ref, ParkedAmendment.MarkRecoverable(recoverable))

    /** Applies [spec] to [subscription]'s parked [ref], or its subscription's spec again when null. */
    suspend fun applyRetrySpec(subscription: SubscriptionId, ref: AggregateRef, spec: ParkedRetrySpec?): ParkedView? =
        amend(subscription, ref, ParkedAmendment.ApplyRetrySpec(spec))

    private suspend fun amend(subscription: SubscriptionId, ref: AggregateRef, amendment: ParkedAmendment): ParkedView? {
        val (scope, _) = locate(subscription, ref) ?: return null
        return scope.ledger.amend(subscription.name, ref, amendment)?.let { scope.view(it) }
    }

    private suspend fun request(scope: Scope, subscription: SubscriptionId, ref: AggregateRef, request: LedgerRequest) =
        if (scope.ledger.amend(subscription.name, ref, ParkedAmendment.Request(request)) != null) {
            Outcome.REQUESTED
        } else {
            Outcome.NOT_PARKED
        }

    /**
     * The scope that holds [subscription]'s entry of [ref], with the entry: among the scopes with the subscription (each
     * log group of its context runs it), those whose filter covers [ref]'s type first.
     */
    private suspend fun locate(subscription: SubscriptionId, ref: AggregateRef): Pair<Scope, ParkedAggregate>? {
        val holders = scopes().filter { it.holds(subscription) }
        val (covering, others) = holders.partition { it.covers(subscription.name, ref.type) }
        for (scope in covering + others) {
            scope.ledger.parked(subscription.name, ref)?.let { return scope to it }
        }
        return null
    }

    /** What [resume] or [skip] came to. */
    @InternalWowApi
    enum class Outcome {
        /** Handled (or skipped) here: the entry is resolved. */
        RESOLVED,

        /** Resumed here and failed again: the entry stays parked, from where it failed. */
        PARKED,

        /** Left for the owner of the entry's partition, which runs it on its next scan. */
        REQUESTED,

        /** The aggregate is not parked by the subscription. */
        NOT_PARKED,

        /**
         * Refused: the entry was parked at another time than the action named, resolved and parked again since, so
         * acting on it would act on a failure nobody looked at.
         */
        STALE,
    }

    /**
     * What acts on a scope's entries on this node: whether it [runs] an entry's partition, and so publishes the entry;
     * whether it [acts] on one at once rather than leaving a request to the owner; and how it
     * [resume]s or [skip]s one, true when the entry is resolved.
     */
    internal interface Runner {
        suspend fun runs(subscription: String, ref: AggregateRef): Boolean

        suspend fun acts(subscription: String, ref: AggregateRef): Boolean = runs(subscription, ref)

        suspend fun resume(subscription: String, ref: AggregateRef): Boolean

        suspend fun skip(subscription: String, ref: AggregateRef): Boolean
    }

    /**
     * Where a node's subscriptions keep their entries: the [ledger] of the subscriptions [names] lists, retried by
     * [options], acted on by [runner] when this node has one, [covers] telling whether a subscription takes a type and
     * [contextOf] the context a subscription belongs to.
     */
    internal class Scope(
        val group: String,
        val names: () -> Set<String>,
        val ledger: FailureLedger,
        val options: ParkedRetryOptions,
        val runner: () -> Runner?,
        val covers: (String, AggregateType) -> Boolean,
        val contextOf: (String) -> String?,
    ) {
        /** The subscriptions of the scope, by their contexts and names. */
        fun ids(): List<SubscriptionId> = names().mapNotNull { name ->
            contextOf(
                name
            )?.let { SubscriptionId(it, name) }
        }

        /** Whether the scope holds [subscription]'s entries: it runs a subscription of that name in that context. */
        fun holds(subscription: SubscriptionId): Boolean =
            subscription.name in names() && contextOf(subscription.name) == subscription.context

        fun view(entry: ParkedAggregate): ParkedView {
            val context = checkNotNull(contextOf(entry.subscription)) {
                "The scope of group $group holds no subscription ${entry.subscription}."
            }
            return ParkedView(entry, options.specOf(entry, context), context, group)
        }

        companion object {
            fun of(runtime: SubscriptionRuntime): Scope = Scope(
                runtime.group,
                { runtime.subscriptionNames },
                runtime.ledger,
                runtime.options.parked,
                { runnerOf(runtime) },
                runtime::covers,
                runtime::contextOf,
            )

            /**
             * The parked timers of [context]'s log [group] sent by [timers] over a log of [partitions] partitions,
             * each entry a target whose timers are parked ([TimerOptions.PARKED_SUBSCRIPTION]).
             * Nothing retries them automatically: they wait for an operator. Resuming or skipping changes the timers
             * in their store, so any node acts at once; the node that runs the target's partition of the group
             * publishes the entry.
             */
            fun of(group: String, context: String, timers: TimerRunner, partitions: Int): Scope {
                val names = setOf(TimerOptions.PARKED_SUBSCRIPTION)
                val runner = object : Runner {
                    override suspend fun runs(subscription: String, ref: AggregateRef): Boolean =
                        Partitioner.partitionOf(ref, partitions) in timers.partitions

                    override suspend fun acts(subscription: String, ref: AggregateRef): Boolean = true

                    override suspend fun resume(subscription: String, ref: AggregateRef): Boolean {
                        timers.resume(ref)
                        return true
                    }

                    override suspend fun skip(subscription: String, ref: AggregateRef): Boolean {
                        timers.skip(ref)
                        return true
                    }
                }
                return Scope(
                    group,
                    { names },
                    timers.ledger,
                    ParkedRetryOptions(spec = ParkedRetrySpec(maxRetries = 0)),
                    { runner },
                    { _, _ -> true },
                    { context },
                )
            }

            private fun runnerOf(runtime: SubscriptionRuntime): Runner = object : Runner {
                override suspend fun runs(subscription: String, ref: AggregateRef): Boolean =
                    runtime.runsPartitionOf(subscription, ref)

                override suspend fun resume(subscription: String, ref: AggregateRef): Boolean =
                    runtime.resume(subscription, ref)

                override suspend fun skip(subscription: String, ref: AggregateRef): Boolean =
                    runtime.skip(subscription, ref)
            }

            fun of(topic: TopicSubscriptions, options: ParkedRetryOptions): Scope {
                val names = topic.subscriptions.mapTo(HashSet()) { it.name }
                return Scope(
                    topic.group.name,
                    { names },
                    topic.ledger,
                    options,
                    { topic.runtime?.let(::runnerOf) },
                    { name, type -> topic.subscriptions.any { it.name == name && it.filter.context == type.context } },
                    { name -> topic.subscriptions.firstOrNull { it.name == name }?.context },
                )
            }
        }
    }

    @InternalWowApi
    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 1_000

        /**
         * The entries of [runtimes]' subscriptions, and the parked timers of [timers], each runner with the runtime of
         * its log group and the partitions of the group's log, for a host that runs them without a [ServiceNode].
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            runtimes: Collection<SubscriptionRuntime>,
            timers: Map<TimerRunner, Pair<SubscriptionRuntime, Int>> = emptyMap(),
        ): ParkedOps {
            val scopes = runtimes.map { Scope.of(it) } +
                timers.map { (runner, group) -> Scope.of(group.first.group, runner.context, runner, group.second) }
            return ParkedOps { scopes }
        }

        /** The ledger's aggregate order (context, aggregate name, tenant, id), each compared by code point. */
        private val REF_ORDER: Comparator<AggregateRef> = Comparator { left, right ->
            sequenceOf(
                left.type.context to right.type.context,
                left.type.name to right.type.name,
                left.tenantId to right.tenantId,
                left.id to right.id,
            ).map { (one, other) -> byCodePoint(one, other) }.firstOrNull { it != 0 } ?: 0
        }

        private fun byCodePoint(left: String, right: String): Int {
            val one = left.codePoints().iterator()
            val other = right.codePoints().iterator()
            while (one.hasNext() && other.hasNext()) {
                val compared = one.nextInt().compareTo(other.nextInt())
                if (compared != 0) {
                    return compared
                }
            }
            return one.hasNext().compareTo(other.hasNext())
        }
    }
}

/** A subscription whose entries park, by the log or lease [group] it runs in and its [name]. */
@InternalWowApi
data class ParkedSubscription(val group: String, val name: String) : Comparable<ParkedSubscription> {
    override fun compareTo(other: ParkedSubscription): Int = compareValuesBy(this, other, { it.group }, { it.name })
}

/** Where a listing of parked entries stands: an entry of [subscription] parking [ref]. */
@InternalWowApi
data class ParkedKey(val subscription: SubscriptionId, val ref: AggregateRef)

/** One page of [ParkedOps.list]: its [entries], and the key the [next] page starts after (null at the end). */
@InternalWowApi
data class ParkedPage(val entries: List<ParkedView>, val next: ParkedKey?)

/**
 * A parked [entry] as an operator sees it, with the [spec] it is retried by, the [context] its subscription belongs
 * to, whose command topic takes the entry's ledger commands, and the log or lease [group] it runs in.
 */
@InternalWowApi
data class ParkedView(val entry: ParkedAggregate, val spec: ParkedRetrySpec, val context: String, val group: String) {
    /** The subscription that parked the entry. */
    val id: SubscriptionId get() = SubscriptionId(context, entry.subscription)

    /** When the next automatic retry is due (epoch milliseconds), null when there is none. */
    val nextRetryAt: Long? get() = entry.nextRetryAt(spec)

    val status: Status
        get() = when {
            entry.unrecoverable || entry.recoverable == Recoverable.UNRECOVERABLE -> Status.UNRECOVERABLE
            entry.exhausted(spec) -> Status.EXHAUSTED
            else -> Status.SCHEDULED
        }

    /** Whether an entry is retried automatically. */
    @InternalWowApi
    enum class Status {
        /** Retried automatically, at [nextRetryAt]. */
        SCHEDULED,

        /** Its spec's retries are spent (9.x: not retryable): it waits for an operator. */
        EXHAUSTED,

        /**
         * Not retried automatically: an operator marked it so, or its source no longer holds what it is parked from,
         * so it can only be skipped.
         */
        UNRECOVERABLE,
    }
}
