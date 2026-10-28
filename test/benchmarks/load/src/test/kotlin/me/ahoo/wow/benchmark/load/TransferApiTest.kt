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

package me.ahoo.wow.benchmark.load

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow

/** The wait mapping of each scenario, on the wire (test/benchmarks/README.md). */
class TransferApiTest {
    private val base = URI.create("http://127.0.0.1:8080")

    @Test
    fun `a command should wait for committed, snapshot or the chain to the target's commit`() {
        val stored = TransferApi.deposit(base, "acc-1", 1, Wait.STORED)
        stored.uri().path.assert().isEqualTo("/transfer/account/acc-1/deposit")
        stored.headers().firstValue("Wow-Wait").get().assert().isEqualTo("committed")
        TransferApi.deposit(base, "acc-1", 1, Wait.SNAPSHOT).headers().firstValue("Wow-Wait").get()
            .assert().isEqualTo("snapshot")
        TransferApi.prepare(base, "acc-1", "acc-2", 1).headers().firstValue("Wow-Wait").get()
            .assert().isEqualTo("committed > handled(TransferSaga.onPrepared) > committed")
        TransferApi.state(base, "acc-1").uri().path.assert().isEqualTo("/transfer/account/acc-1/state")
    }

    @Test
    fun `a list query should spell its filter as the server reads it`() {
        val list = TransferApi.list(base, 10)
        list.uri().path.assert().isEqualTo("/transfer/account/snapshot/query")
        body(list).assert().isEqualTo("""{"kind":"list","filter":{"op":"match-all"},"limit":10}""")
    }

    private fun body(request: HttpRequest): String {
        val bytes = CompletableFuture<ByteArray>()
        request.bodyPublisher().get().subscribe(
            object : Flow.Subscriber<ByteBuffer> {
                private val out = java.io.ByteArrayOutputStream()

                override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)

                override fun onNext(item: ByteBuffer) {
                    out.write(ByteArray(item.remaining()).also { item.get(it) })
                }

                override fun onError(throwable: Throwable) {
                    bytes.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    bytes.complete(out.toByteArray())
                }
            },
        )
        return String(bytes.get(), Charsets.UTF_8)
    }

    @Test
    fun `answers should be read as the server writes them`() {
        val v10 = """{"commandId":"c","ref":{"tenantId":"(0)","id":"acc-2"},"status":"succeeded","version":3}"""
        TransferApi.commandError(200, v10).assert().isNull()
        TransferApi.commandVersion(v10).assert().isEqualTo(3)
        TransferApi.commandAggregate(v10).assert().isEqualTo("acc-2")
        TransferApi.commandError(
            409,
            """{"code":"wow.command.busy"}"""
        ).assert().isEqualTo("http-409-wow.command.busy")
        TransferApi.readError(200, "{}").assert().isNull()
        TransferApi.readError(429, """{"code":"wow.query.busy"}""").assert().isEqualTo("read-http-429-wow.query.busy")
        TransferApi.readError(503, "").assert().isEqualTo("read-http-503")
    }

    @Test
    fun `results should be written as JSON`() {
        Json.write(mapOf("a" to listOf(1, 2), "b" to "x\"y", "c" to null)).assert()
            .isEqualTo("{\n  \"a\": [1, 2],\n  \"b\": \"x\\\"y\",\n  \"c\": null\n}")
    }
}
