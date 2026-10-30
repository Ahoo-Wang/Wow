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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.node.ParkedKey
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.ParkedView
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.ledger.LedgerAggregate
import me.ahoo.wow.spi.ledger.LedgerEntry
import me.ahoo.wow.spi.ledger.LedgerResolution
import me.ahoo.wow.spi.ledger.LedgerRetrySpec
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where and how often a service publishes its failure ledger: to the `parked` aggregate of [context],
 * through its command topic, scanning the ledger every [interval]. [automatic] publishes only while that command topic
 * exists on the broker: the ops service creates it, so it exists once ops is deployed.
 */
class LedgerPublishing @InternalWowApi constructor(
    val context: String = Ledger.OPS_CONTEXT,
    val interval: Duration = DEFAULT_INTERVAL,
    val automatic: Boolean = false,
) {
    init {
        require(context.isNotBlank()) { "The ledger is published to a context." }
        require(interval.isPositive()) { "The ledger is scanned at a positive interval." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is LedgerPublishing && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LedgerPublishing(context=$context, interval=$interval, automatic=$automatic)"

    private fun valueFields(): List<Any?> = listOf(context, interval, automatic)

    companion object {
        /** A LedgerPublishing of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            context: String = Ledger.OPS_CONTEXT,
            interval: Duration = DEFAULT_INTERVAL,
            automatic: Boolean = false
        ): LedgerPublishing = LedgerPublishing(
            context,
            interval,
            automatic
        )

        val DEFAULT_INTERVAL: Duration = 30.seconds
    }
}

/**
 * Publishes a service's failure ledger to the ops context: each entry as a
 * [LedgerEntry] to the ops aggregate [Ledger.parkedId] names, through the command topic of
 * [LedgerPublishing.context] ([send]), and each entry that is no longer parked as a [LedgerResolution].
 *
 * - **When**: at once when an entry parks or spends its retries (the node's [ParkedNotifier]), and when an operator's
 *   ledger command acts on one ([parked], [resolved]); and on every [scan], every [LedgerPublishing.interval], the
 *   reconciliation loop: every entry whose partition this node runs ([ParkedOps.owns]) is published again, and an entry
 *   this node published that has since left the ledger, not to another owner, is published as resolved. A record that
 *   is lost (the broker down, the node killed) is so published again; one entry's failure does not stop the others.
 * - **Idempotence**: a record's request id is deterministic: a digest of the entry's ops id and its content, and of
 *   when this node first saw that content, so a record sent twice is one request to the ops aggregate, while an entry
 *   that comes back to an earlier content (an operator's request taken, say) is a new request. The ops aggregate
 *   ignores a record that changes nothing, so the records a scan sends again leave its log as it is.
 * - **Operator**: the records are a framework component's, `system:ledger`.
 *
 * After a restart the node no longer knows what it published: an entry resolved while it was down stays parked in the
 * ops view until an operator acts on it there, which the owning service answers with [LedgerResolution.NOT_PARKED].
 *
 * With [available] (an [LedgerPublishing.automatic] publishing), it publishes only while [available]
 * says the ops context can take the records: asked at [start] and every [availability] after. While it cannot,
 * nothing is sent and the entries stay in the ledger; once it can, a [scan] at once publishes them all. Each change is
 * logged once.
 */
class LedgerPublisher internal constructor(
    private val service: String,
    private val options: LedgerPublishing,
    private val parked: ParkedOps,
    private val send: suspend (CommandWire) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val ids: GlobalIdGenerator = GlobalIdGenerator.UUID_V7,
    /** Where the reconciliation loop runs. */
    private val context: CoroutineContext = Dispatchers.Default,
    /** Whether the ops context can take the records now; null when it always can. */
    private val available: (suspend () -> Boolean)? = null,
    /** How often [available] is asked. */
    private val availability: Duration = options.interval,
) {
    private val lock = Mutex()

    /** Whether the records are sent: always without [available], else as it last said. */
    @Volatile
    var open: Boolean = available == null
        private set

    @Volatile
    private var gate: Job? = null

    /** The entries this node published as parked, by key: what it published and when it first saw that content. */
    private val published = HashMap<ParkedKey, Observed>()

    /**
     * The entries an operator's action is acting on ([acting]), with how many actions: a scan leaves them to the
     * action, which publishes what came of it.
     */
    private val acting = ConcurrentHashMap<ParkedKey, Int>()

    @Volatile
    private var loop: Job? = null

    private class Observed(val record: LedgerEntry, val at: Long)

    /** Hears when an entry parks or spends its retries, and publishes it as it now stands. */
    val notifier: ParkedNotifier = ParkedNotifier { notice: ParkedNotice ->
        parked(ParkedView(notice.entry, notice.spec, notice.context, notice.group))
    }

    /** Publishes [view]'s entry as parked. */
    @InternalWowApi
    suspend fun parked(view: ParkedView) {
        if (open) lock.withLock { publishParked(view) }
    }

    /** Publishes that [subscription] no longer parks [ref], since [outcome]; [failedAt] the parking's, when known. */
    @InternalWowApi
    suspend fun resolved(
        subscription: SubscriptionId,
        ref: AggregateRef,
        outcome: String,
        failedAt: Long? = null,
    ): Unit = if (!open) {
        Unit
    } else {
        lock.withLock {
            val key = ParkedKey(subscription, ref)
            val known = published.remove(key)?.record
            publishResolved(key, failedAt ?: known?.failedAt, outcome)
        }
    }

    /**
     * Runs [action], an operator's action on [subscription]'s entry of [ref] that publishes what came of it, while
     * the scans leave the entry alone: a scan between the action resolving the entry and publishing its outcome would
     * otherwise publish it as [LedgerResolution.RESOLVED] first, and the ops view keeps the first resolution.
     */
    @InternalWowApi
    suspend fun <T> acting(subscription: SubscriptionId, ref: AggregateRef, action: suspend () -> T): T {
        val key = ParkedKey(subscription, ref)
        acting.merge(key, 1, Int::plus)
        try {
            return action()
        } finally {
            acting.computeIfPresent(key) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }

    /**
     * Publishes every entry whose partition this node runs, and as resolved each one it published before that has
     * left the ledger since; an entry an operator's action is acting on is the action's to publish.
     */
    suspend fun scan(): Unit = if (!open) {
        Unit
    } else {
        lock.withLock {
            val seen = HashSet<ParkedKey>()
            var after: ParkedKey? = null
            do {
                val page = parked.list(after = after, limit = ParkedOps.MAX_LIMIT)
                page.entries.forEach { view ->
                    val key = ParkedKey(view.id, view.entry.ref)
                    if (parked.owns(key.subscription, key.ref)) {
                        seen += key
                        if (!acting.containsKey(key)) {
                            publishParked(view)
                        }
                    }
                }
                after = page.next
            } while (after != null)
            (published.keys - seen - acting.keys).forEach { key ->
                val gone = published.getValue(key).record
                // An entry whose partition moved to another node is that node's to publish now.
                if (parked.get(key.subscription, key.ref) == null) {
                    publishResolved(key, gone.failedAt, LedgerResolution.RESOLVED)
                }
                published.remove(key)
            }
        }
    }

    /**
     * Starts the reconciliation loop: a [scan] now and every [LedgerPublishing.interval]; with [available], asks it now
     * and every [availability] first.
     */
    fun start() {
        check(loop == null) { "The ledger of $service is published already." }
        available?.let { ask ->
            gate = CoroutineScope(context).launch {
                while (isActive) {
                    refresh(ask)
                    delay(availability)
                }
            }
        }
        loop = CoroutineScope(context).launch {
            while (isActive) {
                try {
                    scan()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                    logger.warn(failure) { "Failed to scan the ledger of $service for ${options.context}." }
                }
                delay(options.interval)
            }
        }
    }

    /** Stops the reconciliation loop, waiting for a scan that is running. */
    suspend fun stop() {
        gate?.cancelAndJoin()
        gate = null
        loop?.cancelAndJoin()
        loop = null
    }

    /** Asks [ask] whether the ops context can take the records, opening or closing the gate; scans once it opens. */
    @Suppress("TooGenericExceptionCaught") // A failed check keeps the gate as it is; the next one asks again.
    internal suspend fun refresh(ask: suspend () -> Boolean) {
        val now = try {
            ask()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to check whether ${options.context} takes the ledger of $service." }
            return
        }
        if (now == open) {
            return
        }
        open = now
        if (now) {
            logger.info { "${options.context} takes the failure ledger now: $service publishes it (the ops publication)." }
            try {
                scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(failure) { "Failed to scan the ledger of $service for ${options.context}." }
            }
        } else {
            logger.info { "${options.context} does not take the failure ledger: $service stops publishing it." }
        }
    }

    private suspend fun publishParked(view: ParkedView) {
        val entry = view.entry
        val key = ParkedKey(view.id, entry.ref)
        val now = clock.millis()
        val record = LedgerEntry(
            service = service,
            context = view.context,
            subscription = entry.subscription,
            aggregate = LedgerAggregate.of(entry.ref),
            fromVersion = entry.fromVersion,
            fromPosition = entry.fromPosition,
            reason = entry.reason,
            failedAt = entry.failedAt,
            retries = entry.retries,
            retryAt = entry.retryAt,
            retrySpec = LedgerRetrySpec(
                maxRetries = view.spec.maxRetries,
                initialBackoff = view.spec.initialBackoff.toIsoString(),
                timeout = view.spec.timeout.toIsoString(),
                applied = entry.retrySpec != null,
            ),
            recoverable = WireNames.of(entry.recoverable),
            request = entry.request?.let(WireNames::of),
            status = WireNames.of(view.status),
            nextRetryAt = view.nextRetryAt,
            recordedAt = now,
        )
        val content = record.copyWith(recordedAt = 0)
        val observed = published[key]?.takeIf { it.record == content } ?: Observed(content, now)
        published[key] = observed
        val requestId = digest(key, ClusterCodec.mapper.writeValueAsString(content), observed.at)
        trySend(key, Ledger.RECORD_PARKED, mapOf(Ledger.ENTRY to record), requestId)
    }

    private suspend fun publishResolved(key: ParkedKey, failedAt: Long?, outcome: String) {
        val record = LedgerResolution(
            service = service,
            context = key.subscription.context,
            subscription = key.subscription.name,
            aggregate = LedgerAggregate.of(key.ref),
            failedAt = failedAt,
            outcome = outcome,
            resolvedAt = clock.millis(),
        )
        // A parking is resolved once; its failure time tells it from the next one of the same aggregate.
        val requestId = digest(key, "resolved:${failedAt ?: record.resolvedAt}", 0)
        trySend(key, Ledger.RECORD_RESOLVED, mapOf(Ledger.RESOLUTION to record), requestId)
    }

    /** Sends [body], the record under its member, as the command [command] of [key]'s ops aggregate; a failure is logged, the next scan sends it again. */
    private suspend fun trySend(key: ParkedKey, command: String, body: Any, requestId: String) {
        val target = AggregateType(options.context, Ledger.PARKED)
            .ref(Caller.DEFAULT_TENANT, parkedIdOf(key))
        val wire = CommandWire(
            type = "${target.type}.$command",
            id = ids.next(),
            requestId = requestId,
            ref = target,
            body = ClusterCodec.mapper.valueToTree(body),
            operator = OPERATOR,
        )
        try {
            send(wire)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            logger.warn(failure) { "Failed to publish $command of ${key.ref} by ${key.subscription} to $target." }
        }
    }

    private fun parkedIdOf(key: ParkedKey): String =
        Ledger.parkedId(service, key.subscription.context, key.subscription.name, key.ref)

    private fun digest(key: ParkedKey, content: String, at: Long): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${parkedIdOf(key)}\n$content\n$at".toByteArray())
        return "${Ledger.NAMESPACE}:" + bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** The operator of the records: the ledger, a framework component. */
        @JvmField
        val OPERATOR: Operator = Operator.system("ledger")

        private val logger = KotlinLogging.logger {}
    }
}
