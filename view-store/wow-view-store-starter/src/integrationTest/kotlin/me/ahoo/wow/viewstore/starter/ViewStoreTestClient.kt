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

package me.ahoo.wow.viewstore.starter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import reactor.core.publisher.Flux
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.TimeoutException

/**
 * A client of the view store's host that reads every response body before the call returns, a status-only check
 * included. A response whose body is never read keeps its pooled connection: when the body arrives after the
 * headers, nothing reads it, so the connection never goes back to the pool.
 */
internal fun viewStoreTestClient(port: String): WebTestClient =
    WebTestClient.bindToServer().baseUrl("http://localhost:$port").filter(READ_EVERY_BODY).build()

private val READ_EVERY_BODY = ExchangeFilterFunction.ofResponseProcessor { response ->
    response.bodyToMono(ByteArray::class.java)
        .defaultIfEmpty(ByteArray(0))
        .map { bytes ->
            val body: Flux<DataBuffer> = Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))
            response.mutate().body(body).build()
        }
}

/**
 * When a call times out, the test fails with what led up to it: the last debug events of Reactor Netty's server,
 * client and connection pool and of Spring's web handler (whether the server got the request, and on which
 * connection), and a dump of every thread. Those loggers write only to an in-memory ring, so a passing run logs
 * nothing more.
 */
internal class ConnectionDiagnostics : BeforeEachCallback, TestExecutionExceptionHandler {
    companion object {
        private const val RECENT_EVENTS = 600
        private val LOGGERS = listOf(
            "reactor.netty.http",
            "reactor.netty.resources",
            "reactor.netty.channel.ChannelOperations",
            "org.springframework.web.server.adapter.HttpWebHandlerAdapter",
        )
        private val recent = RecentEvents(RECENT_EVENTS)
    }

    /** Spring Boot resets logging when it starts a context, so the loggers are routed again before each test. */
    override fun beforeEach(context: ExtensionContext) {
        if (!recent.isStarted) {
            recent.context = LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
            recent.start()
        }
        LOGGERS.forEach { name ->
            val logger = LoggerFactory.getLogger(name) as Logger
            logger.level = Level.DEBUG
            logger.isAdditive = false
            if (logger.getAppender(RecentEvents.NAME) == null) {
                logger.addAppender(recent)
            }
        }
    }

    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
        if (generateSequence(throwable) { it.cause }.none { it is TimeoutException }) {
            throw throwable
        }
        val report = buildString {
            appendLine("${throwable.message}")
            appendLine("--- The last ${recent.size()} HTTP and connection pool events ---")
            recent.events().forEach { appendLine(it) }
            appendLine("--- Threads at ${Instant.now()} ---")
            append(threadDump())
        }
        throw IllegalStateException(report, throwable)
    }

    private fun threadDump(): String = buildString {
        ManagementFactory.getThreadMXBean().dumpAllThreads(true, true).forEach { thread ->
            append("\"${thread.threadName}\" #${thread.threadId} ${thread.threadState}")
            thread.lockName?.let { append(" on $it") }
            thread.lockOwnerName?.let { append(" owned by \"$it\"") }
            appendLine()
            thread.stackTrace.forEachIndexed { depth, frame ->
                appendLine("\tat $frame")
                thread.lockedMonitors.filter { it.lockedStackDepth == depth }.forEach { appendLine("\t- locked $it") }
            }
            thread.lockedSynchronizers.forEach { appendLine("\t- locked synchronizer $it") }
            appendLine()
        }
    }

    /** The last events of the diagnostic loggers; one at INFO or above is also printed, as it was before. */
    private class RecentEvents(private val capacity: Int) : AppenderBase<ILoggingEvent>() {
        companion object {
            const val NAME = "view-store-recent-events"
        }

        private val ring = ArrayDeque<String>(capacity)

        init {
            name = NAME
        }

        override fun append(event: ILoggingEvent) {
            val line = "${Instant.ofEpochMilli(event.timeStamp)} [${event.threadName}] ${event.level} " +
                "${event.loggerName} - ${event.formattedMessage}"
            if (event.level.isGreaterOrEqual(Level.INFO)) {
                println(line)
            }
            synchronized(ring) {
                if (ring.size == capacity) {
                    ring.removeFirst()
                }
                ring.addLast(line)
            }
        }

        fun size(): Int = synchronized(ring) { ring.size }

        fun events(): List<String> = synchronized(ring) { ring.toList() }
    }
}
