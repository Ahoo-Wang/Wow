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

package me.ahoo.wow.tck.query.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SnapshotQueryBackendTestKitTest : SnapshotQueryBackendSpec() {
    private val documents = CopyOnWriteArrayList<PortableStoredQueryDocument>()
    internal val observableFactory = InMemoryObservableQueryBackendFactory(documents::toList)
    private val factoryCalls = AtomicLong()
    internal val backendFactoryCalls: Long
        get() = factoryCalls.get()
    internal var materializedFactory: ObservableQueryBackendFactory? = null
        private set

    override fun backendFactory(): ObservableQueryBackendFactory = observableFactory.also { factory ->
        factoryCalls.incrementAndGet()
        materializedFactory = factory
    }

    override fun prepare(dataset: PortableQueryDataset): Mono<Void> = Mono.fromRunnable {
        documents.clear()
        documents += dataset.snapshotDocuments
    }

    override fun clear(): Mono<Void> = Mono.fromRunnable(documents::clear)
}

class EventStreamQueryBackendTestKitTest : EventStreamQueryBackendSpec() {
    private val documents = CopyOnWriteArrayList<PortableStoredQueryDocument>()
    private val observableFactory = InMemoryObservableQueryBackendFactory(documents::toList)

    override fun backendFactory(): ObservableQueryBackendFactory = observableFactory

    override fun prepare(dataset: PortableQueryDataset): Mono<Void> = Mono.fromRunnable {
        documents.clear()
        documents += dataset.eventStreamDocuments
    }

    override fun clear(): Mono<Void> = Mono.fromRunnable(documents::clear)
}

class ObservableQueryBackendFactoryBindingTest {
    @Test
    fun `downstream cancellation materializes one factory and observes its client publisher`() {
        val spec = SnapshotQueryBackendTestKitTest()

        spec.`downstream cancellation cancels backend client publisher`()

        assertFactoryBinding(spec)
    }

    @Test
    fun `deadline materializes one factory and observes its client publisher`() {
        val spec = SnapshotQueryBackendTestKitTest()

        spec.`deadline cancels backend client publisher before its first item`()

        assertFactoryBinding(spec)
    }

    private fun assertFactoryBinding(spec: SnapshotQueryBackendTestKitTest) {
        assertEquals(1, spec.backendFactoryCalls)
        assertSame(spec.observableFactory, spec.materializedFactory)
        assertEquals(1, spec.observableFactory.subscriptionCount)
        assertEquals(1, spec.observableFactory.cancellationCount)
    }
}
