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

import me.ahoo.wow.query.backend.QueryBackendFactory
import reactor.core.publisher.Mono
import java.util.concurrent.CopyOnWriteArrayList

class SnapshotQueryBackendTestKitTest : SnapshotQueryBackendSpec() {
    private val documents = CopyOnWriteArrayList<PortableStoredQueryDocument>()

    override fun backendFactory(): QueryBackendFactory = QueryBackendFactory { context ->
        InMemoryPortableQueryBackend(context, documents::toList)
    }

    override fun prepare(dataset: PortableQueryDataset): Mono<Void> = Mono.fromRunnable {
        documents.clear()
        documents += dataset.snapshotDocuments
    }

    override fun clear(): Mono<Void> = Mono.fromRunnable(documents::clear)
}

class EventStreamQueryBackendTestKitTest : EventStreamQueryBackendSpec() {
    private val documents = CopyOnWriteArrayList<PortableStoredQueryDocument>()

    override fun backendFactory(): QueryBackendFactory = QueryBackendFactory { context ->
        InMemoryPortableQueryBackend(context, documents::toList)
    }

    override fun prepare(dataset: PortableQueryDataset): Mono<Void> = Mono.fromRunnable {
        documents.clear()
        documents += dataset.eventStreamDocuments
    }

    override fun clear(): Mono<Void> = Mono.fromRunnable(documents::clear)
}
