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

package me.ahoo.wow.benchmark.load

import com.sun.tools.attach.VirtualMachine
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The heap allocations of another JVM on this machine, read with the JDK alone: the Attach API starts the JVM's local
 * management agent (or finds it started), and its `ThreadMXBean` answers the bytes all its threads, the finished ones
 * included, allocated since it started (JDK 21). [connect] attaches once, before the measured phase (B.O-2: starting
 * the agent costs the target a moment); each [Reader.read] after is one call over the open connection, which waits at
 * most [READ_TIMEOUT] for the answer: a call whose answer the connection lost hung for minutes, past the run's drain
 * (#7109).
 */
object Allocations {
    /** How long a read waits for the JVM's answer before it gives the figure up. */
    val READ_TIMEOUT: Duration = 5.seconds

    /**
     * An open connection to one JVM's `ThreadMXBean`, whose [allocated] call each [read] makes on a thread of its own,
     * waiting at most [timeout]. A read that fails or does not answer in time gives no figure, and [missing] says why.
     */
    class Reader internal constructor(
        private val connector: AutoCloseable,
        private val allocated: () -> Long,
        private val timeout: Duration = READ_TIMEOUT,
    ) : AutoCloseable {
        /** Why the last read gave no figure; null while every read answered. */
        @Volatile
        var missing: String? = null
            private set

        private val calls = Executors.newSingleThreadExecutor { task ->
            Thread(task, "allocation-read").apply { isDaemon = true }
        }

        fun read(): Long? {
            val call = calls.submit(Callable { allocated() })
            return try {
                call.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                call.cancel(true)
                missed("the allocation read did not answer in $timeout")
            } catch (failure: ExecutionException) {
                missed("cannot read the allocations: ${failure.cause}")
            }
        }

        private fun missed(reason: String): Long? {
            System.err.println("[alloc] $reason")
            missing = reason
            return null
        }

        override fun close() {
            calls.shutdownNow()
            try {
                connector.close()
            } catch (_: java.io.IOException) {
                // The JVM is gone (a hand-over's stopped node): nothing to close.
            }
        }
    }

    fun connect(pid: Long): Reader? = try {
        val vm = VirtualMachine.attach(pid.toString())
        val address = try {
            vm.startLocalManagementAgent()
        } finally {
            vm.detach()
        }
        val connector = JMXConnectorFactory.connect(JMXServiceURL(address))
        val bean = ManagementFactory.newPlatformMXBeanProxy(
            connector.mBeanServerConnection,
            ManagementFactory.THREAD_MXBEAN_NAME,
            com.sun.management.ThreadMXBean::class.java,
        )
        Reader(connector, { bean.totalThreadAllocatedBytes })
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        System.err.println("[alloc] cannot attach to pid $pid: $failure")
        null
    }
}
