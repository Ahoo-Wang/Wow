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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.PeerRestored.Reason
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** The bypasses of [SilentPeers] by themselves, without a transport: what each call keeps, and what it leaves alone. */
class SilentPeersStateTest {
    private val events = CopyOnWriteArrayList<ObservedEvent>()
    private val now = AtomicLong(0)
    private val silent = SilentPeers(
        object : Observer {
            override fun observed(event: ObservedEvent) {
                events += event
            }
        },
        30.seconds,
        now::get,
    )
    private val targets = listOf("http://a:9093", "http://b:9093")

    @Test
    fun `a list with nothing bypassed should be chosen as it is, and keep no state without a generation`() {
        silent.choose("mail", Port.INTERNAL, targets, null).assert().isSameAs(targets)
        silent.bypassed("mail").assert().isEmpty()
        // An answer of a node no list bypassed changes nothing, of a context known or not.
        silent.answered("mail", Port.INTERNAL, "http://a:9093")
        silent.choose("mail", Port.INTERNAL, targets, 1).assert().isSameAs(targets)
        silent.answered("mail", Port.INTERNAL, "http://a:9093")
        events.assert().isEmpty()
    }

    @Test
    fun `a node bypassed twice should be told once, and an answer of another node should not bring it back`() {
        silent.choose("mail", Port.INTERNAL, targets, 1)
        silent.silenced("mail", Port.INTERNAL, "http://a:9093")
        silent.silenced("mail", Port.INTERNAL, "http://a:9093")
        silent.answered("mail", Port.INTERNAL, "http://b:9093")
        silent.choose("mail", Port.INTERNAL, targets, 1).assert().containsExactly("http://b:9093")
        events.assert().containsExactly(ObservedEvent.PeerBypassed("mail", "http://a:9093"))
    }

    @Test
    fun `a bypass of no known generation should not end as relisted, only lapse`() {
        // Bypassed before any list was chosen from: its generation is unknown.
        silent.silenced("mail", Port.INTERNAL, "http://a:9093")
        silent.choose("mail", Port.INTERNAL, targets, 5).assert().containsExactly("http://b:9093")
        silent.bypassed("mail").assert().containsExactly("http://a:9093")
        now.addAndGet(30.seconds.inWholeNanoseconds)
        silent.choose("mail", Port.INTERNAL, targets, 5).assert().isEqualTo(targets)
        events.last().assert().isEqualTo(ObservedEvent.PeerRestored("mail", "http://a:9093", 30_000, Reason.LAPSED))
    }

    @Test
    fun `a list read again should end only the bypasses it ends, the others held`() {
        val three = targets + "http://c:9093"
        silent.choose("mail", Port.INTERNAL, three, 1)
        silent.silenced("mail", Port.INTERNAL, "http://a:9093")
        silent.silenced("mail", Port.INTERNAL, "http://c:9093")
        // Read again without c, of the same generation: c is gone, a still bypassed.
        silent.choose("mail", Port.INTERNAL, targets, 1).assert().containsExactly("http://b:9093")
        silent.bypassed("mail").assert().containsExactly("http://a:9093")
        events.filterIsInstance<ObservedEvent.PeerRestored>().map { it.target to it.reason }.assert()
            .containsExactly("http://c:9093" to Reason.UNLISTED)
    }

    @Test
    fun `a peer list should say its generation and its targets`() {
        val list = PeerList(listOf(URI.create("http://a:9093")), 3)
        list.generation.assert().isEqualTo(3)
        list.single().assert().isEqualTo(URI.create("http://a:9093"))
        list.toString().assert().isEqualTo("PeerList(generation=3, http://a:9093)")
    }
}
