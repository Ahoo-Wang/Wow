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

@file:JvmSynthetic

package me.ahoo.wow.elasticsearch.query.backend

import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.util.WeakHashMap

internal enum class ElasticsearchQueryOperation {
    OPEN_PIT,
    SEARCH,
    CLOSE_PIT,
    COUNT,
}

internal data class ElasticsearchQueryOperationContext(
    val operation: ElasticsearchQueryOperation,
    val pitId: String? = null,
)

internal interface ElasticsearchQueryPublisherObserver {
    fun <T : Any> observe(context: ElasticsearchQueryOperationContext, publisher: Mono<T>): Mono<T>

    fun updatePitId(pitId: String) = Unit
}

internal object ElasticsearchQueryPublisherObservers {
    private val observers = WeakHashMap<ReactiveElasticsearchClient, ElasticsearchQueryPublisherObserver>()

    fun install(client: ReactiveElasticsearchClient, observer: ElasticsearchQueryPublisherObserver) {
        synchronized(observers) { observers[client] = observer }
    }

    fun resolve(client: ReactiveElasticsearchClient): ElasticsearchQueryPublisherObserver = synchronized(observers) {
        observers[client]
    } ?: object : ElasticsearchQueryPublisherObserver {
        override fun <T : Any> observe(context: ElasticsearchQueryOperationContext, publisher: Mono<T>): Mono<T> =
            publisher
    }
}
