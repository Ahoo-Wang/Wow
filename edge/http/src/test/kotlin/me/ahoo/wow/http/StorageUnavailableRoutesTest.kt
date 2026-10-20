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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryStorageUnavailableException
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

/**
 * A store that is unavailable (C.1 finding F2) is answered on every route as the retryable `wow.storage.unavailable`:
 * a 503 problem with `Retry-After`, a command's with its FAILED result, never the server's bare 500.
 */
class StorageUnavailableRoutesTest {
    private fun Answer.assertUnavailable() {
        status.assert().isEqualTo(503)
        contentType.assert().isEqualTo("application/problem+json")
        val retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER)
        retryAfter.assert().isEqualTo(ErrorStatuses.STORAGE_RETRY_AFTER_SECONDS.toString())
        body!!["code"].asString().assert().isEqualTo(FrameworkErrors.STORAGE_UNAVAILABLE)
        body!!["type"].asString().assert().isEqualTo("urn:wow:error:${FrameworkErrors.STORAGE_UNAVAILABLE}")
    }

    @Test
    fun `a command whose request id cannot be looked up should be the retryable problem, its retry committing once`() {
        val node = HttpNode()
        val request = mapOf(WowHeaders.REQUEST_ID to "add-1")
        node.unavailable = true
        val failed = node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = request)
        failed.assertUnavailable()
        failed.body!!["detail"].asString().assert().isEqualTo("The log is unavailable; retry later.")
        val result = failed.body!!["result"]
        result["status"].asString().assert().isEqualTo("FAILED")
        result["requestId"].asString().assert().isEqualTo("add-1")
        result["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        result["error"]["code"].asString().assert().isEqualTo(FrameworkErrors.STORAGE_UNAVAILABLE)
        node.commands.assert().isEmpty()

        node.unavailable = false
        val retried = node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = request)
        retried.status.assert().isEqualTo(200)
        retried.body!!["version"].asInt().assert().isEqualTo(1)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = request)
            .body!!["duplicate"].asBoolean().assert().isTrue()
        node.commands.assert().hasSize(1)
    }

    @Test
    fun `a state read on a store that is down should be the retryable problem`() {
        val node = HttpNode()
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        node.unavailable = true
        node.get("/shop/cart/c-1/state?version=1").assertUnavailable()
        node.get("/shop/cart/c-1/state/trace").assertUnavailable()
    }

    @Test
    fun `an events query on a log that is down should be the retryable problem, not a query failure`() {
        val node = QueryNode()
        node.http.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        node.http.unavailable = true
        val answer = node.query(
            """{"kind":"list","filter":{"op":"AGGREGATE_ID","value":"c-1"}}""",
            model = QueryRoutes.EVENTS
        )
        answer.assertUnavailable()
        answer.body!!["detail"].asString().assert().isEqualTo("Query storage is unavailable; retry later.")
    }

    @Test
    fun `a snapshot query whose storage is down should be the retryable problem`() {
        val node = QueryNode(
            snapshots = { gateway ->
                object : QueryGateway by gateway {
                    override suspend fun count(query: ICountQuery, caller: QueryCaller): CountResult =
                        throw QueryStorageUnavailableException(StorageUnavailableException("down"))
                }
            },
        )
        node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""").assertUnavailable()
    }
}
