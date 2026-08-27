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
import org.reactivestreams.Publisher
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

internal class ElasticsearchPointInTime(
    private val client: ReactiveElasticsearchClient,
    private val indexName: String,
    keepAlive: Duration,
) {
    internal val keepAliveValue = keepAlive.toMillis().let {
        if (it % 60_000 == 0L) "${it / 60_000}m" else "${it}ms"
    }

    internal class Session(var id: String) {
        fun update(next: String?) {
            next?.takeIf(String::isNotBlank)?.let { id = it }
        }
    }

    fun <T : Any> use(block: (Session) -> Publisher<T>): Flux<T> = Flux.usingWhen(
        open(),
        { session -> Flux.from(block(session)) },
        ::close,
        { session, _ -> close(session) },
        ::close,
    )

    private fun open(): Mono<Session> {
        return Mono.defer {
            client.openPointInTime(
                OpenPointInTimeRequest.of {
                    it.index(indexName)
                        .keepAlive { it.time(keepAliveValue) }
                }
            )
        }.map {
            check(it.id().isNotBlank()) { "Elasticsearch returned an empty PIT ID." }
            Session(it.id())
        }
    }

    private fun close(session: Session): Mono<Void> {
        return Mono.defer {
            client.closePointInTime(ClosePointInTimeRequest.of { it.id(session.id) })
        }.doOnNext {
            if (!it.succeeded()) {
                log.warn { "Failed to close Elasticsearch PIT [${session.id}]." }
            }
        }.then()
            .onErrorResume {
                log.warn(it) { "Failed to close Elasticsearch PIT [${session.id}]." }
                Mono.empty()
            }
    }

    private companion object {
        val log = KotlinLogging.logger(ElasticsearchPointInTime::class.java.name)
    }
}
