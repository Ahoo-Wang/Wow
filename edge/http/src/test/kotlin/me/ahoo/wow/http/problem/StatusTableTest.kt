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

package me.ahoo.wow.http.problem

import io.mockk.coEvery
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.http.CART_CLOSED
import me.ahoo.wow.http.CART_EMPTY
import me.ahoo.wow.http.CONTEXT
import me.ahoo.wow.http.TENANT
import me.ahoo.wow.http.answer
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.http.headerCallers
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.CommandGateway
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.test.web.reactive.server.WebTestClient

/** Every row of the status table (design 05 §3, decisions D102, D103, D121), through a route and in [ErrorStatuses]. */
class StatusTableTest {
    private fun result(code: String, status: CommandResult.Status, hop: Hop = Hop.committed(CONTEXT)) = CommandResult(
        commandId = "cmd-1",
        requestId = "req-1",
        correlationId = "cmd-1",
        ref = AggregateType(CONTEXT, "cart").ref(TENANT, "c-1"),
        hop = hop,
        function = FunctionInfo(FunctionKind.COMMAND, CONTEXT, "cart", "AddItem"),
        status = status,
        error = ErrorInfo(code, "Because.", listOf(Violation("sku", "NotBlank", "must not be blank"))),
        signalTime = 1L,
    )

    @ParameterizedTest
    @MethodSource("rows")
    fun `a result that did not succeed is answered with the status of its code`(code: String, status: Int) {
        val failed = result(code, CommandResult.Status.FAILED)
        val gateway = mockk<CommandGateway> { coEvery { execute(any()) } returns failed }
        val client = WebTestClient.bindToRouterFunction(
            wowRoutes(cartManifest(), gateway, callerResolver = headerCallers)
        )
            .build()
        val answer = client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice")
            .header("Content-Type", "application/json").bodyValue("{}").answer()
        answer.status.assert().isEqualTo(status)
        answer.contentType.assert().isEqualTo("application/problem+json")
        val problem = answer.body!!
        problem["type"].asString().assert().isEqualTo("urn:wow:error:$code")
        problem["status"].asInt().assert().isEqualTo(status)
        problem["detail"].asString().assert().isEqualTo("Because.")
        problem["code"].asString().assert().isEqualTo(code)
        problem["violations"][0]["code"].asString().assert().isEqualTo("NotBlank")
        problem["result"]["commandId"].asString().assert().isEqualTo("cmd-1")
        problem["result"]["status"].asString().assert().isEqualTo("FAILED")
    }

    @Test
    fun `the table follows FrameworkErrors`() {
        val statuses = ErrorStatuses(Manifest())
        FrameworkErrors.ALL.forEach { statuses.status(it.code).assert().isEqualTo(it.status) }
        FrameworkCodes.ALL.forEach { FrameworkErrors.BY_CODE.keys.assert().contains(it) }
    }

    @Test
    fun `version mismatch is 412 with If-Match, 409 without`() {
        val statuses = ErrorStatuses(cartManifest())
        statuses.status(FrameworkCodes.VERSION_MISMATCH).assert().isEqualTo(409)
        statuses.status(FrameworkCodes.VERSION_MISMATCH, CONTEXT, ifMatch = true).assert().isEqualTo(412)
        statuses.status(FrameworkCodes.EXISTS, CONTEXT, ifMatch = true).assert().isEqualTo(409)
    }

    @Test
    fun `domain codes take their context's status, else 422, and the host's above all`() {
        val statuses = ErrorStatuses(cartManifest(), mapOf(FrameworkCodes.BUSY to 503))
        statuses.status(CART_EMPTY, CONTEXT).assert().isEqualTo(409)
        statuses.status(CART_EMPTY, "other").assert().isEqualTo(422)
        statuses.status(CART_CLOSED, CONTEXT).assert().isEqualTo(422)
        statuses.status(FrameworkCodes.BUSY).assert().isEqualTo(503)
        statuses.status("wow.not.a.code").assert().isEqualTo(500)
        statuses.title(CART_EMPTY, CONTEXT, 409).assert().isEqualTo("The cart has no item.")
        statuses.title(CART_CLOSED, CONTEXT, 422).assert().isEqualTo("Unprocessable Content")
    }

    @Test
    fun `a problem without a code is about blank and leaves out what it does not have`() {
        val problem = Problem.ofStatus(org.springframework.http.HttpStatus.NOT_FOUND)
        problem.type.assert().isEqualTo(Problem.ABOUT_BLANK)
        problem.members().keys.assert().containsExactly("type", "title", "status")
    }

    companion object {
        @JvmStatic
        fun rows(): List<Arguments> = listOf(
            Arguments.of(FrameworkCodes.AUTH_FORBIDDEN, 403),
            Arguments.of(FrameworkCodes.AGGREGATE_FORBIDDEN, 403),
            Arguments.of(FrameworkCodes.MALFORMED, 400),
            Arguments.of(FrameworkCodes.INVALID, 400),
            Arguments.of(FrameworkCodes.WAIT_INVALID, 400),
            Arguments.of(FrameworkCodes.UNHANDLED, 400),
            Arguments.of(FrameworkCodes.NOT_FOUND, 404),
            Arguments.of(FrameworkCodes.EXISTS, 409),
            Arguments.of(FrameworkCodes.VERSION_MISMATCH, 409),
            Arguments.of(FrameworkCodes.REQUEST_ID_REUSED, 409),
            Arguments.of(FrameworkCodes.CONTENDED, 409),
            Arguments.of(FrameworkCodes.DELETED, 410),
            Arguments.of(FrameworkCodes.BUSY, 429),
            Arguments.of(FrameworkCodes.UNKNOWN, 503),
            Arguments.of(FrameworkCodes.NODE_CLOSING, 503),
            Arguments.of(FrameworkCodes.SUBSCRIPTION_PARKED, 500),
            Arguments.of(FrameworkCodes.WAIT_TIMEOUT, 504),
            Arguments.of(CART_EMPTY, 409),
            Arguments.of(CART_CLOSED, 422),
        )
    }
}
