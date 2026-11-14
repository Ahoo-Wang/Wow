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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The outcomes a node already names to its observers, counted for the `bench-pipeline` line's `outcomes` section,
 * cumulative from the node's start:
 *
 * - `admit.<outcome>`: the commands that arrived at this node's intake, admitted or refused, and `refused.<code>`, every
 *   refusal by its code (a busy lane, a forward to a peer with its most in flight, or one answered busy or withdrawn
 *   past its deadline);
 * - `forward.in`: the forwards this node took from its peers (each resumed once, `Observer.resuming`), and
 *   `forward.<outcome>`: its own forwards that failed (`busy`: the peer had its most in flight, refused busy and run
 *   nowhere, also counted in `refused.wow.command.busy`; `local`: it surely did not reach the peer, then run here
 *   without the partition's lease; `unknown`: no reply, not run here);
 * - `execute.<outcome>`: the commands this node's kernel executed, its own partitions', its peers' forwards and those
 *   it ran without the lease alike;
 * - `append.<outcome>`: each stream's append as the log answered it;
 * - `cache.<outcome>`: the state cache's hits, misses, evictions and stale states (a state behind a commit another
 *   node made in this node's partition, dropped when its tail reads it).
 *
 * What a node runs without the lease is `forward.local`, plus what it runs with no owner known to
 * forward to and what a peer forwards into a partition it has not started yet, which no observer is told of: on the
 * owner, those commits show as `cache.stale`, and on the node that ran them, as `cache.miss` (a node without the lease
 * takes no state from its cache). An observer bean the host composes with any other, only with the line on: a
 * [LongAdder] add an observation, nothing else on the path.
 */
class BenchOutcomes : Observer {
    private val admissions = counters<ObservedOutcome>()
    private val appends = counters<ObservedOutcome>()
    private val executions = counters<ObservedOutcome>()
    private val cache = counters<ObservedEvent.StateCache.Outcome>()
    private val forwards = counters<ObservedEvent.ForwardFailed.Outcome>()
    private val forwardsIn = LongAdder()
    private val refusals = ConcurrentHashMap<String, LongAdder>()
    private val admitting = counting(admissions)
    private val appending = counting(appends)
    private val executing = counting(executions)

    override fun admitting(
        context: String,
        aggregate: String,
        command: String,
        trace: Map<String, String>,
    ): Observation = admitting

    override fun appending(stream: EventStreamRecord): Observation = appending

    override fun executing(command: Command<*>, commandType: String): Observation = executing

    override fun resuming(trace: Map<String, String>): CoroutineContext {
        forwardsIn.increment()
        return EmptyCoroutineContext
    }

    override fun observed(event: ObservedEvent) {
        when (event) {
            is ObservedEvent.StateCache -> cache.getValue(event.outcome).increment()
            is ObservedEvent.ForwardFailed -> forwards.getValue(event.outcome).increment()
            is ObservedEvent.Refused -> refusals.computeIfAbsent(event.code) { LongAdder() }.increment()
            else -> Unit
        }
    }

    /** The counts so far of each outcome seen, by the names above. */
    fun counts(): Map<String, Number> = linkedMapOf<String, Number>().apply {
        named("admit", admissions) { it.value }
        refusals.toSortedMap().forEach { (code, adder) -> put("refused.$code", adder.sum()) }
        forwardsIn.sum().takeIf { it > 0 }?.let { put("forward.in", it) }
        named("forward", forwards) { it.value }
        named("execute", executions) { it.value }
        named("append", appends) { it.value }
        named("cache", cache) { it.name.lowercase() }
    }

    private fun <K : Enum<K>> MutableMap<String, Number>.named(
        prefix: String,
        counts: Map<K, LongAdder>,
        name: (K) -> String,
    ) {
        counts.forEach { (key, adder) -> adder.sum().takeIf { it > 0 }?.let { put("$prefix.${name(key)}", it) } }
    }

    private companion object {
        inline fun <reified K : Enum<K>> counters(): Map<K, LongAdder> =
            EnumMap<K, LongAdder>(K::class.java).apply { enumValues<K>().forEach { put(it, LongAdder()) } }

        /** One stateless observation for every stage of a kind: the outcome it ends with, counted. */
        fun counting(into: Map<ObservedOutcome, LongAdder>): Observation = object : Observation {
            override fun outcome(outcome: ObservedOutcome) = into.getValue(outcome).increment()
        }
    }
}
