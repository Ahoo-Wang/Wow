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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.observation.ObservedEvent

/*
 * The background loops of a node (RT-5). Each one runs through [launchLoop] or [launchWorker], which hold the property
 * the node's liveness rests on: no loop ends while its scope runs without the node going not-live.
 *
 * A round handles its own failures, as each loop always did: it logs one and waits as it says, so a loop's timing is
 * its round's alone. Whatever escapes a round while the loop's scope is still active ends the loop, for nothing
 * restarts it, and is recorded by the node's [NodeFatals], told through the scope's context ([NodeFatals.contextOf]):
 *
 * - a fatal error ([NodeFatals.isFatal]) as any other fatal error: the node steps out and its liveness is `DOWN`;
 * - anything else (an `Error` such as a failed initializer, a linkage error, an assertion or a stack overflow in the
 *   loop's own code, or an exception the round did not catch) as the loop's end ([NodeFatals.ended]): the liveness is
 *   `DOWN`, naming the loop ([endedLiveness]), and the node holds its partitions. The memory is sound, unlike after a
 *   fatal error, so nothing says they are unsafe where they are: an error that follows a partition's data would fell
 *   every node that took the partition over, one after another, and an error of the build is the same on every node,
 *   where stepping out would stop every write at once. The orchestrator restarts the process, with the error in plain
 *   sight. Where the renewals' loop is the one that ended, the partitions' gates close at their unrenewed limit as for
 *   any renewal that fails, so the leases lapse and another node takes them over.
 *
 * A loop ends quietly when its scope is cancelled (a stop), or when a worker's round says it is done ([launchWorker]).
 * A scope built without a node's [NodeFatals] (a part built on its own, in a test) logs the end at ERROR.
 */

/** Runs [round] in this scope again and again, until the scope stops; see the file description for what ends it. */
internal fun CoroutineScope.launchLoop(loop: String, round: suspend () -> Unit): Job =
    launchWorker(loop) {
        round()
        true
    }

/**
 * Runs [round] in this scope again and again while it returns true, as a lane that ends once its channel closes, and
 * until the scope stops; see the file description for what ends it otherwise.
 */
internal fun CoroutineScope.launchWorker(loop: String, round: suspend () -> Boolean): Job = launch {
    try {
        var going = true
        while (going && isActive) {
            going = round()
        }
    } catch (cancelled: CancellationException) {
        if (!isActive) {
            throw cancelled
        }
        // Not the loop's own stop: a cancellation that escaped a round, as of a timeout.
        NodeFatals.ended(coroutineContext, loop, cancelled)
    } catch (@Suppress("TooGenericExceptionCaught") escaped: Throwable) {
        NodeFatals.ended(coroutineContext, loop, escaped)
    }
}

/**
 * A background [loop] of a node that ended ([NodeFatals.ended]): one of [component]'s work on [name], with an [error]
 * of that class (its full name), at [at] (epoch milliseconds).
 */
internal data class EndedLoop(
    val component: ObservedEvent.Fatal.Component,
    val name: String,
    val loop: String,
    val error: String,
    val at: Long,
)

/**
 * The liveness of [node] once one of its loops ended ([NodeFatals.ended]): `DOWN` for good, naming the first loop
 * that ended as [ENDED], with how many did; null while none has. No event is observed for it: `ObservedEvent.Fatal`
 * means the node stepped out, which a loop's end does not make it do.
 */
internal fun endedLiveness(node: ServiceNode, now: Long): ComponentHealth? = node.fatals.endedLoop?.let { ended ->
    ComponentHealth(
        NodeHealth.LIVENESS,
        HealthStatus.DOWN,
        linkedMapOf(
            "state" to node.state.name,
            ENDED to linkedMapOf(
                "component" to ended.component.value,
                "name" to ended.name,
                "loop" to ended.loop,
                "error" to ended.error,
                "endedMillis" to (now - ended.at).coerceAtLeast(0),
            ),
            "endedLoops" to node.fatals.endedCount,
        ),
    )
}

/** The liveness detail that names the first loop that ended ([endedLiveness]). */
internal const val ENDED: String = "ended"
