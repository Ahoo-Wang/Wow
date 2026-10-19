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

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import java.time.Duration

/**
 * A point in time over one index, which pages of one stream read so the index does not change under them: opened when
 * the stream starts, closed when it ends, fails or is cancelled.
 */
internal class ElasticsearchPointInTime(
    private val client: ElasticsearchAsyncClient,
    private val indexName: String,
    keepAlive: Duration,
) {
    internal val keepAliveValue = keepAlive.toMillis().let {
        if (it % MILLIS_PER_MINUTE == 0L) "${it / MILLIS_PER_MINUTE}m" else "${it}ms"
    }

    internal class Session(var id: String) {
        fun update(next: String?) {
            next?.takeIf(String::isNotBlank)?.let { id = it }
        }
    }

    /** The flow of [block] over a point in time opened for it and closed after it. */
    fun <T> use(block: suspend FlowCollector<T>.(Session) -> Unit): Flow<T> = flow {
        val session = open()
        try {
            block(session)
        } finally {
            withContext(NonCancellable) {
                // A point in time not closed expires with its keep-alive: closing it is cleanup, not the result.
                runCatching { close(session) }
            }
        }
    }

    private suspend fun open(): Session {
        val response = client.openPointInTime(
            OpenPointInTimeRequest.of { request ->
                request.index(indexName).keepAlive { it.time(keepAliveValue) }
            },
        ).awaitElasticsearch()
        checkExecution(response.id().isNotBlank()) { "Elasticsearch returned an empty PIT ID." }
        return Session(response.id())
    }

    private suspend fun close(session: Session) {
        client.closePointInTime(ClosePointInTimeRequest.of { it.id(session.id) }).awaitElasticsearch()
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
