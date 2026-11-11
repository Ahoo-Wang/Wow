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
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spring.boot.query.EventQueryBackend
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** What the tests of a host's query plane share: the account type, opening an account, and waiting for a condition. */
internal object HostQueryFixtures {
    val ACCOUNT = AggregateType(BANK, "account")

    fun Host.openAccount(owner: String, headers: Map<String, String> = emptyMap()): String {
        val opened = post("/bank/account/open-account", """{"owner":"$owner"}""", headers)
        opened.status.assert().isEqualTo(200)
        return opened.body!!["ref"]["id"].asString()
    }

    fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition did not hold in time." }
            Thread.sleep(20)
        }
    }
}

/** A snapshot store that also stores each snapshot it writes into the in-memory reference query backend. */
class QueriedSnapshots(private val store: SnapshotStore, private val queries: InMemoryQueryStorage) :
    SnapshotStore by store {
    override suspend fun save(snapshot: SnapshotRecord): Boolean =
        store.save(snapshot).also { written -> if (written) queries.save(snapshot) }

    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> =
        store.saveAll(snapshots).also { outcomes ->
            outcomes.forEachIndexed { index, outcome -> if (outcome.written) queries.save(snapshots[index]) }
        }
}

/**
 * The memory backend with its snapshots queried: the application's [me.ahoo.wow.query.backend.QueryBindings], the
 * in-memory reference backend, fed by each context's snapshot store.
 */
@Configuration(proxyBeanMethods = false)
class MemorySnapshotQueries {
    @Bean
    fun memoryQueryStorage(): InMemoryQueryStorage = InMemoryQueryStorage()

    @Bean
    fun memoryBackends(properties: WowProperties, queries: InMemoryQueryStorage): HostBackends =
        HostBackends.memory(properties.log.partitions) { QueriedSnapshots(InMemorySnapshotStore(), queries) }
}

/**
 * The application's own `events` backend, without a snapshot query backend: each context's log scanned in memory, as
 * an application with a [me.ahoo.wow.runtime.node.BackendFactory] of its own would give one.
 */
@Configuration(proxyBeanMethods = false)
class ScannedEvents {
    @Bean
    fun applicationEvents(node: ServiceNode): EventQueryBackend {
        val storages = ConcurrentHashMap<String, InMemoryEventQueryStorage>()
        return EventQueryBackend { type ->
            node.contexts[type.context]?.let { context ->
                storages.computeIfAbsent(type.context) { InMemoryEventQueryStorage.of(context.log) }.binding(type)
            }
        }
    }
}
