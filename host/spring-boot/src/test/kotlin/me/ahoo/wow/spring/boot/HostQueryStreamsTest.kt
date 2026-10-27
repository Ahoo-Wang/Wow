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
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.query.QueryStreams
import org.junit.jupiter.api.Test
import org.springframework.boot.web.server.context.WebServerApplicationContext
import kotlin.time.Duration.Companion.seconds

/** `wow.query.stream.*` (decision D253) and the lifecycle that ends the open streams as the node stops. */
class HostQueryStreamsTest {
    @Test
    fun `the stream keys reach the public port, whose streams end before its graceful shutdown`() {
        val host = startHost(
            "wow.query.stream.chunk-size" to 7,
            "wow.query.stream.max-hold" to "5s",
        )
        val streams = host.context.getBean(HttpQueries::class.java).streams
        streams.chunkSize.assert().isEqualTo(7)
        streams.maxHold.assert().isEqualTo(5.seconds)
        val lifecycle = host.context.getBean(QueryStreamsLifecycle::class.java)
        lifecycle.isRunning.assert().isTrue()
        lifecycle.phase.assert().isGreaterThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE)
        streams.closed.assert().isFalse()
        host.close()
        lifecycle.isRunning.assert().isFalse()
        streams.closed.assert().isTrue()
    }

    @Test
    fun `the stream keys default to 500 records a chunk and a 30s hold`() {
        val defaults = QueryProperties().stream
        defaults.chunkSize.assert().isEqualTo(500)
        defaults.maxHold.assert().isEqualTo(java.time.Duration.ofSeconds(30))
        QueryStreams.DEFAULT_MAX_HOLD.assert().isEqualTo(30.seconds)
    }
}
