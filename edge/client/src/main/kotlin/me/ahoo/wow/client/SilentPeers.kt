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

package me.ahoo.wow.client

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.PeerRestored.Reason
import me.ahoo.wow.spi.observation.Observer
import java.net.URI
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The targets one lookup of a peer's nodes found, and its [generation]: a lookup that finds them again, the same
 * nodes or not, has another, so a client knows the list was read again ([SilentPeers]). What a discovery that caches
 * its lookups answers ([REFRESH]); a resolver that answers plain lists has no generations, and a bypassed node then
 * comes back after [REFRESH].
 */
@InternalWowApi
class PeerList(targets: List<URI>, val generation: Long) : List<URI> by targets {
    override fun toString(): String = "PeerList(generation=$generation, ${joinToString()})"

    @InternalWowApi
    companion object {
        /** How long a peer's list is kept before it is looked up again: the TTL Kubernetes' DNS gives SRV records. */
        val REFRESH: Duration = 30.seconds
    }
}

/**
 * The nodes of each peer context that took a request and did not answer it within its bound: the requests that
 * follow choose among the context's other nodes ([choose]), so a node lost without notice (a pod deleted by force,
 * whose address drops packets) costs one request its bound rather than every request that rotates onto it until the
 * list is read again. It is not a health check: it sends no probe. A command is never sent again to a node that went
 * silent: it is sent again under the same request id at most once, and only to another live node, which forwards it
 * to the partition's owner, so its outcome is settled as a duplicate or a commit; with no other live node it fails
 * once as `wow.command.unknown`.
 *
 * - **Bypassed** ([silenced]): a request sent to the node timed out without an answer. A connection that was not made
 *   is not a bypass: the request fails over to the next node, as before.
 * - **Back** ([Reason]): when it answers a request still in flight to it, any whole response ([answered]); when the
 *   list is read again with it ([PeerList.generation]); or after [lapse], the time a list is kept, with no list read
 *   again. A connection made to it does not bring it back: a node that takes connections and never answers would pass.
 *   A list read again without it removes it.
 * - **Every node bypassed:** the requests go to them all, as if none were: a request is never left unsent for it.
 *   The resend of a request a node went silent on is the exception: it goes to a live node or nowhere.
 *
 * One per node, shared by its clients of every kind: a saga's calls, a service-mode client's commands and queries go
 * to a peer's internal port, a user-mode client's to its public port. The bypasses are kept per list, a context's
 * on one [Port]: a request to the internal port that was not answered passes the node over for the next requests to
 * that port, a saga's call, a command or a query alike, and never for the other port's, whose list is another. A
 * node is the same machine on both ports, but whether it answers on the other is that port's requests' to show, and
 * one list's lookup ends no bypass of the other's. Each bypass and each return is logged once, and observed
 * ([ObservedEvent.PeerBypassed], [ObservedEvent.PeerRestored]).
 *
 * The choice of a request's targets reads it on every request: with nothing bypassed it is two map lookups (the
 * port's, then the context's) and a volatile read, without a lock or an allocation. A bypass and a return take the context's lock; they are rare.
 */
@InternalWowApi
class SilentPeers @JvmOverloads constructor(
    private val observer: Observer = Observer.NONE,
    private val lapse: Duration = PeerList.REFRESH,
    /** The monotonic time in nanoseconds a bypass is timed by. */
    private val clock: () -> Long = System::nanoTime,
) {
    /** The bypasses of each port's lists, by context: one list per context and port. */
    private val ports: Map<Port, ConcurrentHashMap<String, Context>> =
        EnumMap<Port, ConcurrentHashMap<String, Context>>(Port::class.java).apply {
            Port.entries.forEach { put(it, ConcurrentHashMap()) }
        }

    /** When a node was bypassed, and the [generation] of its context's list then, null when the list has none. */
    private class Mark(val since: Long, val generation: Long?)

    /** The bypassed nodes of one list, a context's on a port, by base URL, and its generation last chosen from. */
    private class Context {
        @Volatile
        var marks: Map<String, Mark> = emptyMap()

        @Volatile
        var generation: Long? = null
    }

    private fun listsOf(port: Port): ConcurrentHashMap<String, Context> = ports.getValue(port)

    private fun stateOf(context: String, port: Port): Context {
        val lists = listsOf(port)
        return lists[context] ?: lists.computeIfAbsent(context) { Context() }
    }

    /**
     * The [targets] of [context] on [port] a request goes to, the bypassed ones left out unless all are, in their
     * order; without [all], none then: the resend of a request a node went silent on goes to a live node alone. Before
     * it chooses it settles that list's bypasses against it, its [generation] (null when it has none) and the time;
     * another port's list is not touched.
     */
    internal fun choose(
        context: String,
        port: Port,
        targets: List<String>,
        generation: Long?,
        all: Boolean = true,
    ): List<String> {
        val state = listsOf(port)[context]
        if (state == null) {
            if (generation == null) return targets
            stateOf(context, port).generation = generation
            return targets
        }
        if (generation != null && state.generation != generation) {
            state.generation = generation
        }
        val marks = state.marks
        if (marks.isEmpty()) return targets
        val now = clock()
        // Read without the lock: it is taken only when a bypass ends, which is rare.
        val bypassed = if (marks.any { (target, mark) -> endOf(target, mark, targets, generation, now) != null }) {
            settle(context, state, targets, generation, now)
        } else {
            marks
        }
        if (bypassed.isEmpty()) return targets
        val open = targets.filter { it !in bypassed }
        return if (open.isEmpty() && all) targets else open
    }

    /** Why the bypass of [target], [mark], ends now, against [targets] and their [generation]; null when it holds. */
    private fun endOf(target: String, mark: Mark, targets: List<String>, generation: Long?, now: Long): Reason? = when {
        target !in targets -> Reason.UNLISTED
        generation != null && mark.generation != null && mark.generation != generation -> Reason.RELISTED
        now - mark.since >= lapse.inWholeNanoseconds -> Reason.LAPSED
        else -> null
    }

    /** The bypasses of [context] that still hold against [targets], the others ended as [endOf] says. */
    private fun settle(
        context: String,
        state: Context,
        targets: List<String>,
        generation: Long?,
        now: Long,
    ): Map<String, Mark> {
        val ended = ArrayList<Triple<String, Mark, Reason>>()
        val kept = synchronized(state) {
            val kept = state.marks.filter { (target, mark) ->
                val reason = endOf(target, mark, targets, generation, now)
                reason?.let { ended += Triple(target, mark, it) }
                reason == null
            }
            state.marks = kept
            kept
        }
        ended.forEach { (target, mark, reason) -> restored(context, target, mark, reason, now) }
        return kept
    }

    /**
     * [target] of [context] on [port] took a request and did not answer it within its bound: bypass it in that list,
     * unless it is already.
     */
    internal fun silenced(context: String, port: Port, target: String) {
        val state = stateOf(context, port)
        synchronized(state) {
            if (target in state.marks) return
            state.marks = state.marks + (target to Mark(clock(), state.generation))
        }
        logger.warn {
            "$target of $context took a request and did not answer it within its bound: the requests to $context " +
                "go to its other nodes until it answers one, its list is read again, or $lapse pass."
        }
        observer.observed(ObservedEvent.PeerBypassed(context, target))
    }

    /** [target] of [context] on [port] answered a request, a whole response: it is back if that list bypassed it. */
    internal fun answered(context: String, port: Port, target: String) {
        val state = listsOf(port)[context] ?: return
        if (state.marks.isEmpty() || target !in state.marks) return
        val mark = synchronized(state) {
            val mark = state.marks[target] ?: return
            state.marks = state.marks - target
            mark
        }
        restored(context, target, mark, Reason.ANSWERED, clock())
    }

    /** The nodes of [context] on [port] bypassed now, for a test. */
    internal fun bypassed(context: String, port: Port = Port.INTERNAL): Set<String> =
        listsOf(port)[context]?.marks?.keys.orEmpty()

    private fun restored(context: String, target: String, mark: Mark, reason: Reason, now: Long) {
        val millis = (now - mark.since) / NANOS_PER_MILLI
        logger.info { "$target of $context, bypassed for $millis ms, ${outcomeOf(reason)}." }
        observer.observed(ObservedEvent.PeerRestored(context, target, millis, reason))
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
        private const val NANOS_PER_MILLI = 1_000_000L

        /** What a return for [reason] means, as the log says it. */
        fun outcomeOf(reason: Reason): String = when (reason) {
            Reason.ANSWERED -> "is back in its rotation: it answered a request"
            Reason.RELISTED -> "is back in its rotation: its list was read again and still lists it"
            Reason.UNLISTED -> "is gone: its list was read again and no longer lists it"
            Reason.LAPSED -> "is back in its rotation: no list was read again within the time a list is kept"
        }
    }
}
