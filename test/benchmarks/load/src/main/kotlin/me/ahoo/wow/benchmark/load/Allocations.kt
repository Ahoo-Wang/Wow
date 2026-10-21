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
import javax.management.remote.JMXConnector
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL

/**
 * The heap allocations of another JVM on this machine, read with the JDK alone: the Attach API starts the JVM's local
 * management agent (or finds it started), and its `ThreadMXBean` answers the bytes all its threads, the finished ones
 * included, allocated since it started (JDK 21). [connect] attaches once, before the measured phase (B.O-2: starting
 * the agent costs the target a moment); each [Reader.read] after is one call over the open connection.
 */
object Allocations {
    /** An open connection to one JVM's `ThreadMXBean`. */
    class Reader internal constructor(
        private val connector: JMXConnector,
        private val bean: com.sun.management.ThreadMXBean,
    ) : AutoCloseable {
        fun read(): Long? = try {
            bean.totalThreadAllocatedBytes
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            System.err.println("[alloc] cannot read the allocations: $failure")
            null
        }

        override fun close() = try {
            connector.close()
        } catch (_: java.io.IOException) {
            // The JVM is gone (a hand-over's stopped node): nothing to close.
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
        Reader(
            connector,
            ManagementFactory.newPlatformMXBeanProxy(
                connector.mBeanServerConnection,
                ManagementFactory.THREAD_MXBEAN_NAME,
                com.sun.management.ThreadMXBean::class.java,
            ),
        )
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        System.err.println("[alloc] cannot attach to pid $pid: $failure")
        null
    }
}
