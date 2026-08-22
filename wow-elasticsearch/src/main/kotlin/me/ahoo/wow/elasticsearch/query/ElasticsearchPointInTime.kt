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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

internal class ElasticsearchPointInTime(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val indexName: String,
    keepAlive: Duration,
) {
    val keepAliveValue: String = keepAlive.toMillis().let {
        if (it % 60_000 == 0L) "${it / 60_000}m" else "${it}ms"
    }

    init {
        require(keepAlive.toMillis() > 0) { "keepAlive must be greater than or equal to 1ms." }
    }

    fun <T : Any> use(action: (Session) -> Flux<T>): Flux<T> = Flux.usingWhen(
        open(),
        action,
        ::close,
        { session, _ -> close(session) },
        ::close,
    )

    private fun open(): Mono<Session> = Mono.defer {
        elasticsearchClient.openPointInTime(
            OpenPointInTimeRequest.of {
                it.index(indexName).keepAlive { keepAlive -> keepAlive.time(keepAliveValue) }
            },
        )
    }.map {
        check(it.id().isNotBlank()) { "Elasticsearch returned an empty PIT ID." }
        Session(it.id())
    }

    private fun close(session: Session): Mono<Void> = Mono.defer {
        elasticsearchClient.closePointInTime(ClosePointInTimeRequest.of { it.id(session.id) })
    }.doOnNext {
        if (!it.succeeded()) {
            log.warn { "Failed to close Elasticsearch PIT [${session.id}]." }
        }
    }.then().onErrorResume {
        log.warn(it) { "Failed to close Elasticsearch PIT [${session.id}]." }
        Mono.empty()
    }

    data class Session(var id: String)

    private companion object {
        val log = KotlinLogging.logger(ElasticsearchPointInTime::class.java.name)
    }
}
