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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/** Records every access stamp the node's walks and follower make, by why ([ObservedEvent.AccessStamped.Cause]). */
@Configuration(proxyBeanMethods = false)
class AccessStamps {
    val stamped = CopyOnWriteArrayList<ObservedEvent.AccessStamped>()

    @Bean
    fun accessStampObserver(): Observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            if (event is ObservedEvent.AccessStamped) stamped += event
        }
    }
}

/**
 * A node on a fresh PostgreSQL store with the query plane on (X-119): the query plane creates the log's tables as the
 * host starts, and the creation records each type's walk completed for the node's schema, so commands sent the moment
 * the node has started are never walked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresFreshStoreWalkTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val host = startHost(
        "wow.log.partitions" to 4,
        "spring.datasource.url" to postgres.jdbcUrl,
        "spring.datasource.username" to postgres.username,
        "spring.datasource.password" to postgres.password,
        sources = listOf(HostTestApp::class.java, AccessStamps::class.java),
    )
    private val account = AggregateType(BANK, "account")

    @AfterAll
    fun close() {
        host.close()
        postgres.stop()
    }

    private fun recordedWalk(): String? = host.context.getBean(DataSource::class.java).connection.use { connection ->
        connection.prepareStatement("SELECT access_walk FROM wow_log_aggregate WHERE context = ? AND aggregate = ?")
            .use { query ->
                query.setString(1, BANK)
                query.setString(2, account.name)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
    }

    @Test
    fun `commands sent the moment the node started are recorded walked at the store's creation, and never walked`() {
        repeat(20) {
            host.post("/bank/account/open-account", """{"owner":"alice"}""").body!!["ref"]["id"].asString()
        }
        val walking = checkNotNull(host.node.context(BANK).accessWalking)
        eventually { walking.walks()[account] != null }
        walking.walks().getValue(account).let {
            it.completed.assert().isTrue()
            it.walked.assert().isZero()
            // Recorded by the table's creation, not claimed over the log's heads.
            it.heads.assert().isEmpty()
        }
        // The registry holds the walk the creation wrote.
        recordedWalk().assert().isNotNull()
        host.context.getBean(AccessStamps::class.java).stamped
            .filter { it.cause == ObservedEvent.AccessStamped.Cause.WALK }.assert().isEmpty()
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition did not hold within 10 s." }
            Thread.sleep(20)
        }
    }
}
