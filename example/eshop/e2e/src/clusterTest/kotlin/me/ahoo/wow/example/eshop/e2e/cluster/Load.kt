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

package me.ahoo.wow.example.eshop.e2e.cluster

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Full-chain checkouts on a thread of their own while a fault runs, so it happens mid-checkout. */
class Load(private val flows: ShopFlows) {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private val count = AtomicInteger()
    val sent: Int get() = count.get()

    fun start(): Load = apply {
        running = true
        worker = thread(name = "load", isDaemon = true) {
            while (running) {
                val buyer = "load-${LOADS.incrementAndGet()}"
                runCatching {
                    if (flows.addItem(buyer, "sock", "5.00", 1).status == ShopFlows.OK) {
                        flows.checkout(buyer, mapOf(ShopFlows.WAIT to ShopFlows.FULL_CHAIN, ShopFlows.WAIT_TIMEOUT to "20s"))
                        count.incrementAndGet()
                    }
                }
                Thread.sleep(LOAD_PAUSE.toMillis())
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(Outages.JOIN.toMillis())
    }

    private companion object {
        val LOADS = AtomicInteger()
        val LOAD_PAUSE: Duration = Duration.ofMillis(200)
    }
}
