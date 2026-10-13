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

package me.ahoo.wow.example.shop.testing

import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spring.boot.HostBackends
import me.ahoo.wow.spring.boot.WowProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** A snapshot store that also stores each snapshot it writes into the in-memory reference query backend. */
class QueriedSnapshots(private val store: SnapshotStore, private val queries: InMemoryQueryStorage) :
    SnapshotStore by store {
    override suspend fun save(snapshot: SnapshotRecord): Boolean =
        store.save(snapshot).also { written -> if (written) queries.save(snapshot) }
}

/**
 * The memory backend with its snapshots queried (work package 6.2c): no production query backend serves the memory
 * backend, so the tests give the host the in-memory reference backend of wow-query's test fixtures as its
 * `QueryBackendFactory`, fed by each context's snapshot store. Outside the server's package, so the server's
 * component scan does not pick it up: a test names it.
 */
@Configuration(proxyBeanMethods = false)
class MemorySnapshotQueries {
    @Bean
    fun memoryQueryStorage(): InMemoryQueryStorage = InMemoryQueryStorage()

    @Bean
    fun memoryBackends(properties: WowProperties, queries: InMemoryQueryStorage): HostBackends =
        HostBackends.memory(properties.log.partitions) { QueriedSnapshots(InMemorySnapshotStore(), queries) }
}
