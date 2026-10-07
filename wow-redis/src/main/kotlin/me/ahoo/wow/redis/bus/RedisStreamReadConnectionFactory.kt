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

package me.ahoo.wow.redis.bus

import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.ReactiveRedisClusterConnection
import org.springframework.data.redis.connection.ReactiveRedisConnection
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration
import reactor.core.publisher.Mono

/**
 * The connection factory of one receive stream: every read gets the same connection of [delegate], opened on the first
 * read and closed by [closeLater] when the stream ends.
 *
 * A stream receiver reads with `XREADGROUP … BLOCK`, which Lettuce runs on a dedicated connection rather than the
 * shared one, and it asks its factory for a connection per read and closes it afterwards. Without a pool (Spring
 * Boot's default) that closes the dedicated TCP connection after every read and opens a new one for the next: one
 * connection per batch under load, every one of them left in `TIME_WAIT`. Here the connection, and with it its
 * dedicated connection, lives as long as the stream; the reads are sequential, so one is enough.
 *
 * Used only when the connection factory has no pool ([holdsReadConnection]). A pool already keeps the connections
 * open between reads, and a connection held for the stream's lifetime would take a pool slot for good: with more
 * streams than the pool's `max-active` the extra streams could never read.
 */
internal class RedisStreamReadConnectionFactory(
    private val delegate: ReactiveRedisConnectionFactory,
) : ReactiveRedisConnectionFactory {
    private val lock = Any()

    /** Guarded by [lock]. */
    private var connection: ReactiveRedisConnection? = null

    /** Guarded by [lock]. */
    private var closed = false

    override fun getReactiveConnection(): ReactiveRedisConnection {
        val opened = synchronized(lock) {
            // A read that starts after the stream ended (a late poll racing its cancellation) must not reopen a
            // connection that nothing would close.
            check(!closed) { "The receive stream's connection is closed." }
            connection ?: delegate.reactiveConnection.also { connection = it }
        }
        return KeptOpenConnection(opened)
    }

    override fun getReactiveClusterConnection(): ReactiveRedisClusterConnection =
        throw UnsupportedOperationException("A receive stream reads through getReactiveConnection().")

    override fun translateExceptionIfPossible(ex: RuntimeException): DataAccessException? =
        delegate.translateExceptionIfPossible(ex)

    /** Closes the connection, if a read opened it. */
    fun closeLater(): Mono<Void> = Mono.defer {
        val opened = synchronized(lock) {
            closed = true
            connection.also { connection = null }
        }
        opened?.closeLater() ?: Mono.empty()
    }

    /** The stream's connection as each read sees it: closing it after the read leaves it open. */
    private class KeptOpenConnection(
        connection: ReactiveRedisConnection,
    ) : ReactiveRedisConnection by connection {
        override fun close() = Unit

        override fun closeLater(): Mono<Void> = Mono.empty()
    }

    companion object {
        /**
         * Whether a receive stream should hold one connection for its reads: unless [connectionFactory] is a Lettuce
         * factory configured with a pool (`LettucePoolingClientConfiguration`), whose pool already keeps the
         * connections open between reads.
         */
        fun holdsReadConnection(connectionFactory: ReactiveRedisConnectionFactory): Boolean =
            (connectionFactory as? LettuceConnectionFactory)?.clientConfiguration !is LettucePoolingClientConfiguration
    }
}
