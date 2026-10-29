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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JDK the nodes of a process run on, warned about once when it pins virtual threads.
 *
 * The backends make their blocking driver calls on virtual threads. Before JDK 24, a virtual thread that blocks inside
 * `synchronized` code, as database drivers, Kafka clients and Java agents do, pins the carrier it runs on. Enough
 * such threads leave the scheduler no carrier, so every store and topic call of the node stops returning; the
 * liveness then fails (`drivers`), and the node is restarted. JDK 24 lets those threads unmount. Wow is built for
 * JDK 21 and recommends JDK 25 to run on.
 */
internal class JdkCheck(
    /** The running JDK's feature release, such as 21. */
    private val feature: () -> Int = { Runtime.version().feature() },
    private val warn: (String) -> Unit = { message -> logger.warn { message } },
) {
    private val warned = AtomicBoolean()

    /** Warns, the first time only, when the JDK pins virtual threads; whether this call warned. */
    fun warnOnce(): Boolean {
        val release = feature()
        if (release >= UNPINNED_FROM || !warned.compareAndSet(false, true)) {
            return false
        }
        warn(
            "Wow runs on JDK $release, which pins a virtual thread that blocks inside synchronized code, as database " +
                "drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of " +
                "a node, whose liveness then fails and restarts it. Run Wow on JDK $RECOMMENDED, which does not " +
                "pin them (JDK $UNPINNED_FROM and later)."
        )
        return true
    }

    companion object {
        /** The first JDK whose virtual threads unmount while blocked in `synchronized` code. */
        const val UNPINNED_FROM: Int = 24

        /** The JDK Wow recommends to run on. */
        const val RECOMMENDED: Int = 25

        private val logger = KotlinLogging.logger {}

        /** The process's check: each node starting asks it, and the first warns. */
        val PROCESS: JdkCheck = JdkCheck()
    }
}
