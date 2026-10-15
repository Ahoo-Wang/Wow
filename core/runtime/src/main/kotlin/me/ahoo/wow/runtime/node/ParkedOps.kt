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
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable

/**
 * What an operator does with the parked entries of a node's subscriptions (roadmap P.1, decisions D141, D185): list
 * and read them, resume or skip one, mark one recoverable or not (9.x `MarkRecoverable`), apply a retry spec to one
 * (9.x `ApplyRetrySpec`). The host serves it over HTTP; any node of the service answers for every entry.
 *
 * An entry is the subscription's in the ledger of the context (or the topic) it reads. [resume] and [skip] act at once
 * when this node owns the entry's partition; otherwise they leave a request on the entry ([LedgerRequest]), which the
 * owner takes on its next scan of the parked entries (decision D141), and answer [Outcome.REQUESTED]. Marking an entry
 * and applying a spec write the ledger directly: the owner reads them on its next scan.
 */
@InternalWowApi
class ParkedOps internal constructor(private val scopes: () -> List<Scope>) {
    /** The names of the subscriptions whose entries this node reads, in order. */
    val subscriptions: List<String> get() = scopes().flatMapTo(sortedSetOf()) { it.names() }.toList()

    /**
     * Up to [limit] entries, ordered by subscription name and then by aggregate as [FailureLedger.list] orders them:
     * [subscription]'s only when given, after [after] (exclusive) when given, and only those whose aggregate [visible]
     * admits (a caller's own tenant's, for one that may not act across tenants). [ParkedPage.next] is where the next
     * page starts, null when there is none.
     */
    suspend fun list(
        subscription: String? = null,
        after: ParkedKey? = null,
        limit: Int = DEFAULT_LIMIT,
        visible: (AggregateRef) -> Boolean = { true },
    ): ParkedPage {
        require(limit in 1..MAX_LIMIT) { "A page holds 1 to $MAX_LIMIT entries." }
        val names = (subscription?.let(::listOf) ?: subscriptions).filter { after == null || it >= after.subscription }
        val entries = ArrayList<ParkedView>(limit)
        for (name in names) {
            var refAfter = after?.takeIf { it.subscription == name }?.ref
            while (entries.size < limit) {
                val page = page(name, refAfter, limit)
                for (view in page) {
                    if (visible(view.entry.ref)) {
                        entries += view
                        if (entries.size == limit) {
                            break
                        }
                    }
                }
                if (page.size < limit) {
                    break
                }
                refAfter = page.last().entry.ref
            }
            if (entries.size >= limit) {
                break
            }
        }
        val next = entries.takeIf { it.size == limit }?.last()?.let { ParkedKey(it.entry.subscription, it.entry.ref) }
        return ParkedPage(entries, next)
    }

    /** [subscription]'s entries after [after], at most [limit], from every ledger that holds the subscription's. */
    private suspend fun page(subscription: String, after: AggregateRef?, limit: Int): List<ParkedView> {
        val holders = scopes().filter { subscription in it.names() }
        val byRef = LinkedHashMap<AggregateRef, ParkedView>()
        holders.forEach { scope ->
            scope.ledger.list(subscription, after, limit).forEach { byRef.putIfAbsent(it.ref, scope.view(it)) }
        }
        return byRef.values.sortedWith(compareBy(REF_ORDER) { it.entry.ref }).take(limit)
    }

    /** The entry parking [ref] for [subscription], if any. */
    suspend fun get(subscription: String, ref: AggregateRef): ParkedView? =
        locate(subscription, ref)?.let { (scope, entry) -> scope.view(entry) }

    /**
     * Handles [subscription]'s parked [ref] again from where it is parked, whatever its retry spec says: at once when
     * this node owns its partition, else by a request to the owner.
     */
    suspend fun resume(subscription: String, ref: AggregateRef): Outcome {
        val (scope, _) = locate(subscription, ref) ?: return Outcome.NOT_PARKED
        val runtime = scope.runtime()?.takeIf { it.runsPartitionOf(subscription, ref) }
            ?: return request(scope, subscription, ref, LedgerRequest.RESUME)
        if (runtime.resume(subscription, ref)) {
            return Outcome.RESOLVED
        }
        return if (scope.ledger.isParked(subscription, ref)) Outcome.PARKED else Outcome.NOT_PARKED
    }

    /**
     * Resolves [subscription]'s parked [ref] without handling what was parked: at once when this node owns its
     * partition and no resume of it runs, else by a request to the owner.
     */
    suspend fun skip(subscription: String, ref: AggregateRef): Outcome {
        val (scope, _) = locate(subscription, ref) ?: return Outcome.NOT_PARKED
        val runtime = scope.runtime()?.takeIf { it.runsPartitionOf(subscription, ref) }
            ?: return request(scope, subscription, ref, LedgerRequest.SKIP)
        if (runtime.skip(subscription, ref)) {
            return Outcome.RESOLVED
        }
        // A resume of it runs: the owner (this node) skips it on its next scan, unless the resume resolved it.
        return request(scope, subscription, ref, LedgerRequest.SKIP)
    }

    /**
     * Whether this node runs [subscription]'s partition of [ref] now: it acts on the entry at once, and publishes it
     * (decision D141). False when the aggregate is not parked by it.
     */
    suspend fun owns(subscription: String, ref: AggregateRef): Boolean {
        val (scope, _) = locate(subscription, ref) ?: return false
        return scope.runtime()?.runsPartitionOf(subscription, ref) == true
    }

    /** Marks [subscription]'s parked [ref] [recoverable]; null when it is not parked. */
    suspend fun markRecoverable(subscription: String, ref: AggregateRef, recoverable: Recoverable): ParkedView? =
        amend(subscription, ref, ParkedAmendment.MarkRecoverable(recoverable))

    /** Applies [spec] to [subscription]'s parked [ref], or its subscription's spec again when null. */
    suspend fun applyRetrySpec(subscription: String, ref: AggregateRef, spec: ParkedRetrySpec?): ParkedView? =
        amend(subscription, ref, ParkedAmendment.ApplyRetrySpec(spec))

    private suspend fun amend(subscription: String, ref: AggregateRef, amendment: ParkedAmendment): ParkedView? {
        val (scope, _) = locate(subscription, ref) ?: return null
        return scope.ledger.amend(subscription, ref, amendment)?.let { scope.view(it) }
    }

    private suspend fun request(scope: Scope, subscription: String, ref: AggregateRef, request: LedgerRequest) =
        if (scope.ledger.amend(subscription, ref, ParkedAmendment.Request(request)) != null) {
            Outcome.REQUESTED
        } else {
            Outcome.NOT_PARKED
        }

    /**
     * The scope that holds [subscription]'s entry of [ref], with the entry: among the scopes with the subscription,
     * those whose filter covers [ref]'s type first (a built-in subscription has the same name in every context).
     */
    private suspend fun locate(subscription: String, ref: AggregateRef): Pair<Scope, ParkedAggregate>? {
        val holders = scopes().filter { subscription in it.names() }
        val (covering, others) = holders.partition { it.covers(subscription, ref.type) }
        for (scope in covering + others) {
            scope.ledger.parked(subscription, ref)?.let { return scope to it }
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

        /** Left for the owner of the entry's partition, which runs it on its next scan (decision D141). */
        REQUESTED,

        /** The aggregate is not parked by the subscription. */
        NOT_PARKED,
    }

    /**
     * Where a node's subscriptions keep their entries: the [ledger] of the subscriptions [names] lists, retried by
     * [options], run by [runtime] when this node runs it, [covers] telling whether a subscription takes a type and
     * [contextOf] the context a subscription belongs to.
     */
    internal class Scope(
        val names: () -> Set<String>,
        val ledger: FailureLedger,
        val options: ParkedRetryOptions,
        val runtime: () -> SubscriptionRuntime?,
        val covers: (String, AggregateType) -> Boolean,
        val contextOf: (String) -> String? = { null },
    ) {
        fun view(entry: ParkedAggregate): ParkedView =
            ParkedView(entry, options.specOf(entry), contextOf(entry.subscription))

        companion object {
            fun of(runtime: SubscriptionRuntime): Scope = Scope(
                { runtime.subscriptionNames },
                runtime.ledger,
                runtime.options.parked,
                { runtime },
                runtime::covers,
                runtime::contextOf,
            )

            fun of(topic: TopicSubscriptions, options: ParkedRetryOptions): Scope {
                val names = topic.subscriptions.mapTo(HashSet()) { it.name }
                return Scope(
                    { names },
                    topic.ledger,
                    options,
                    { topic.runtime },
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

        /** The entries of [runtimes]' subscriptions, for a host that runs them without a [ServiceNode]. */
        @JvmStatic
        fun of(runtimes: Collection<SubscriptionRuntime>): ParkedOps {
            val scopes = runtimes.map { Scope.of(it) }
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

/** Where a listing of parked entries stands: an entry of [subscription] parking [ref]. */
@InternalWowApi
data class ParkedKey(val subscription: String, val ref: AggregateRef)

/** One page of [ParkedOps.list]: its [entries], and the key the [next] page starts after (null at the end). */
@InternalWowApi
data class ParkedPage(val entries: List<ParkedView>, val next: ParkedKey?)

/**
 * A parked [entry] as an operator sees it, with the [spec] it is retried by and the [context] its subscription belongs
 * to, whose command topic takes the entry's ledger commands (decision D141); null when the node does not know it.
 */
@InternalWowApi
data class ParkedView(val entry: ParkedAggregate, val spec: ParkedRetrySpec, val context: String? = null) {
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
