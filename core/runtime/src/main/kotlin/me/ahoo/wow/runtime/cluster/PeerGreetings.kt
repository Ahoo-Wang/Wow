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

package me.ahoo.wow.runtime.cluster

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The greetings of the other members that failed and keep failing: how long each has failed for, so a
 * greeting that fails while a new member's address does not resolve yet, the routine race of a rollout, is noted
 * quietly, and one that keeps failing past [patience] (the member TTL) is a warning.
 */
internal class PeerGreetings(private val timeSource: TimeSource, private val patience: Duration) {
    private val failing = ConcurrentHashMap<String, TimeMark>()

    /** The greeting of [peer] failed: whether it has failed for longer than [patience] now, since it first failed. */
    fun failed(peer: String): Boolean = failing.computeIfAbsent(peer) { timeSource.markNow() }.elapsedNow() > patience

    /** How long the greeting of [peer] has failed for; zero when it does not. */
    fun failingFor(peer: String): Duration = failing[peer]?.elapsedNow() ?: Duration.ZERO

    /** The greeting of [peer] succeeded. */
    fun greeted(peer: String) {
        failing.remove(peer)
    }

    /** Forgets the peers that are no longer members. */
    fun retain(peers: Set<String>) {
        failing.keys.retainAll(peers)
    }
}
