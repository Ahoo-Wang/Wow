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

package me.ahoo.wow.benchmark.axon

import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The threads the routes send their commands from, and the transfer's `separate` reactions: the command bus handles a
 * command on the thread that sends it and blocks on JDBC, so never on Netty's event loop. [threads] platform threads,
 * by default as many as Tomcat's (`server.tomcat.threads.max`, 200), what a blocking Spring application serves Axon 4
 * from; past them the commands queue, without a bound.
 *
 * Not virtual threads: on JDK 21 a virtual thread that blocks inside `synchronized` pins its carrier (JEP 491 lifts it
 * in JDK 24), and Axon 4 does so at the end of every handler, whose timeout it cancels under a monitor on its janitor's
 * queue lock (`AxonTimeLimitedTask.complete`). On a runner's two CPUs two such threads held both carriers while the
 * lock's next owner, a virtual thread, waited for one, and no virtual thread ran again (axon-pg-e3-reactions). The
 * benchmark has run on JDK 25 since D393, where such a thread no longer pins its carrier; the pool stays, as a pool of
 * platform threads is how Axon 4's blocking API is deployed.
 */
@Component
class CommandSenders(@Value("\${benchmark.axon.command-threads:200}") threads: Int) : Executor, DisposableBean {
    private val index = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        threads,
        threads,
        0,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { task -> Thread(task, "command-${index.incrementAndGet()}").apply { isDaemon = true } },
    )

    /** The commands waiting for a thread. */
    val queued: Int get() = pool.queue.size

    override fun execute(command: Runnable) {
        pool.execute(command)
    }

    override fun destroy() {
        pool.shutdownNow()
    }
}
