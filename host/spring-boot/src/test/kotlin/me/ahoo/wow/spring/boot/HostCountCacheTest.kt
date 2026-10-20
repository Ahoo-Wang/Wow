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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.query.QueryCountCache
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.spi.observation.Observation
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.toKotlinDuration

/** Records the outcome each query's observation is told, as `<query type>:<outcome>`. */
class OutcomeObserver : QueryObserver {
    val outcomes = CopyOnWriteArrayList<String>()

    override fun start(aggregateType: AggregateType, queryType: QueryType): Observation = object : Observation {
        override fun outcome(outcome: String) {
            outcomes += "$queryType:$outcome"
        }
    }
}

private const val TENANT_HEADER = "Test-Tenant"

/** The [OutcomeObserver], and callers of the tenant the [TENANT_HEADER] names. */
@Configuration(proxyBeanMethods = false)
class QueryOutcomes {
    @Bean
    fun outcomeObserver(): OutcomeObserver = OutcomeObserver()

    @Bean
    fun tenantHeaderCallers(): CallerResolver = CallerResolver { request ->
        Caller(tenantId = request.headers().firstHeader(TENANT_HEADER) ?: Caller.DEFAULT_TENANT)
    }
}

/**
 * The count cache over HTTP on a host (decision D152, work package 6.2d): a repeated count and a repeated paged total
 * are served from the cache, a command that writes a snapshot invalidates both, and each tenant counts its own. The
 * same check runs on PostgreSQL and MongoDB in the integration tests.
 */
fun Host.assertCountCache() {
    val outcomes = context.getBean(OutcomeObserver::class.java).outcomes
    fun cached() = outcomes.filter { it.endsWith(":${QueryCountCache.CACHED_OUTCOME}") }
    val filter = """{"op":"EQ","field":"state.owner","value":"counted"}"""
    fun count(tenant: String = "acme"): Long {
        val counted = post("/bank/account/snapshot/query", """{"kind":"count","filter":$filter}""", tenant)
        counted.status.assert().isEqualTo(200)
        return counted.body!!["count"].asLong()
    }
    fun total(): Long {
        val paged = post(
            "/bank/account/snapshot/query",
            """{"kind":"paged","filter":$filter,"pagination":{"index":1,"size":1}}""",
            "acme",
        )
        paged.status.assert().isEqualTo(200)
        return paged.body!!["total"].asLong()
    }
    fun open(tenant: String = "acme") {
        post("/bank/account/open-account", """{"owner":"counted"}""", tenant).status.assert().isEqualTo(200)
    }

    open()
    count().assert().isEqualTo(1)
    count().assert().isEqualTo(1)
    cached().assert().containsExactly("COUNT:cached")
    // The command waits for its snapshot: the count after it sees the write.
    open()
    count().assert().isEqualTo(2)
    count().assert().isEqualTo(2)
    cached().assert().hasSize(2)

    // A paged total bounded as the count is shares its entry.
    total().assert().isEqualTo(2)
    total().assert().isEqualTo(2)
    cached().assert().containsExactly("COUNT:cached", "COUNT:cached", "PAGED:cached", "PAGED:cached")
    open()
    total().assert().isEqualTo(3)
    cached().assert().hasSize(4)
    total().assert().isEqualTo(3)
    cached().assert().hasSize(5)

    // Another tenant's count is its own.
    count("globex").assert().isEqualTo(0)
    open("globex")
    count("globex").assert().isEqualTo(1)
    count().assert().isEqualTo(3)
}

/** A request of [tenant], which [QueryOutcomes] reads from the [TENANT_HEADER]. */
private fun Host.post(path: String, body: String, tenant: String) =
    post(path, body, mapOf(TENANT_HEADER to tenant))

class HostCountCacheTest {
    @Test
    fun `counts should be cached on the memory backend until a snapshot is written`() {
        startHost(
            sources = listOf(HostTestApp::class.java, MemorySnapshotQueries::class.java, QueryOutcomes::class.java),
        ).use { it.assertCountCache() }
    }

    @Test
    fun `the count cache should follow its properties, and be off when disabled`() {
        startHost(
            "wow.query.count-cache.max-entries" to 5,
            "wow.query.count-cache.ttl" to "2s",
            sources = listOf(HostTestApp::class.java, MemorySnapshotQueries::class.java),
        ).use { host ->
            val options = host.context.getBean(QueryCountCache::class.java).options
            options.maxEntries.assert().isEqualTo(5)
            options.ttl.assert().isEqualTo(Duration.ofSeconds(2).toKotlinDuration())
        }
        startHost(
            "wow.query.count-cache.enabled" to false,
            sources = listOf(HostTestApp::class.java, MemorySnapshotQueries::class.java, QueryOutcomes::class.java),
        ).use { host ->
            host.context.getBeanNamesForType(QueryCountCache::class.java).assert().isEmpty()
            repeat(2) {
                host.post("/bank/account/snapshot/query", """{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
                    .status.assert().isEqualTo(200)
            }
            host.context.getBean(OutcomeObserver::class.java).outcomes.assert().isEmpty()
        }
    }

    @Test
    fun `the events' counts should be cached only when asked, the log running ahead of the snapshots (D259)`() {
        listOf(false, true).forEach { eventsEnabled ->
            startHost(
                "wow.query.count-cache.events-enabled" to eventsEnabled,
                sources = listOf(
                    HostTestApp::class.java,
                    MemorySnapshotQueries::class.java,
                    ScannedEvents::class.java,
                    QueryOutcomes::class.java,
                ),
            ).use { host ->
                repeat(2) {
                    host.post("/bank/account/events/query", """{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
                        .status.assert().isEqualTo(200)
                }
                val cached = host.context.getBean(OutcomeObserver::class.java).outcomes
                    .filter { it.endsWith(":${QueryCountCache.CACHED_OUTCOME}") }
                cached.assert().hasSize(if (eventsEnabled) 1 else 0)
            }
        }
    }
}
