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

package me.ahoo.wow.spring.boot

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.pauses
import me.ahoo.wow.runtime.node.throttled
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** A saga whose derived commands time out while [overloaded] is set, as under a node's overload (decision D263). */
@Configuration(proxyBeanMethods = false)
class OverloadedSaga {
    val overloaded = AtomicBoolean(true)
    val attempts = AtomicInteger()
    val handled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Bean
    fun overloadedSagaFactory(): SubscriptionFactory = SubscriptionFactory { context ->
        if (context.context != BANK) {
            emptyList()
        } else {
            listOf(
                Subscription(SAGA, SubscriptionFilter(BANK)) { entry ->
                    attempts.incrementAndGet()
                    if (overloaded.get()) {
                        throw WowProblemException(
                            FrameworkErrors.WAIT_TIMEOUT,
                            GATEWAY_TIMEOUT,
                            RetryHint(retryable = true, sameRequestId = true),
                            "The derived command on ${entry.ref} failed: ${FrameworkErrors.WAIT_TIMEOUT}",
                        )
                    }
                    handled += entry.ref.id
                },
            )
        }
    }

    companion object {
        const val SAGA = "Overloaded.onAccount"
        private const val GATEWAY_TIMEOUT = 504
    }
}

/**
 * Derived commands that time out under load on a PostgreSQL node (decision D263, cloud cliff run 39412583843): the
 * saga's partitions are throttled and keep going, never paused, nothing is parked in the PostgreSQL ledger, and once
 * the load is gone every account is handled and the throttle lifts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresBackpressureTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val host = startHost(
        "wow.log.partitions" to 2,
        "test.postgres.url" to postgres.jdbcUrl,
        "test.postgres.username" to postgres.username,
        "test.postgres.password" to postgres.password,
        "wow.subscription.retry.max-attempts" to 2,
        "wow.subscription.backpressure.max-attempts" to 1_000,
        "wow.subscription.backpressure.initial-backoff" to "5ms",
        "wow.subscription.backpressure.max-backoff" to "50ms",
        sources = listOf(HostTestApp::class.java, OutagePostgresDataSource::class.java, OverloadedSaga::class.java),
    )
    private val saga: OverloadedSaga get() = host.context.getBean(OverloadedSaga::class.java)

    @AfterAll
    fun close() {
        host.close()
        postgres.stop()
    }

    @Test
    fun `derived commands that time out under load throttle the saga's partitions and never pause them`() {
        val ids = (1..ACCOUNTS).map { openAccount("owner-$it") }
        // Far beyond what pauses a partition (3 aggregates failing within 10s) and each aggregate's own budget.
        eventually { saga.attempts.get() > ACCOUNTS * 2 * 3 && host.node.throttled.isNotEmpty() }
        host.node.pauses.paused().assert().isEmpty()
        host.node.throttled.map { it.subscription }.toSet().assert().containsExactly(OverloadedSaga.SAGA)

        saga.overloaded.set(false)
        eventually { saga.handled.containsAll(ids) }
        eventually { host.node.throttled.isEmpty() }
        host.node.pauses.paused().assert().isEmpty()
        runBlocking { host.node.parked.list(OverloadedSaga.SAGA) }.entries.assert().isEmpty()
    }

    private fun openAccount(owner: String): String {
        val opened = host.post("/bank/account/open-account", """{"owner":"$owner"}""", COMMITTED)
        opened.status.assert().isEqualTo(200)
        return opened.body!!["ref"]["id"].asString()
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition did not hold in time." }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val ACCOUNTS = 6
        const val POLL_MS = 20L
        val DEADLINE = 30.seconds
        val COMMITTED = mapOf(WowHeaders.WAIT to "committed")
    }
}
