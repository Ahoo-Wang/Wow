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

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.Path
import jakarta.validation.Validator
import jakarta.validation.constraints.NotBlank
import jakarta.validation.metadata.ConstraintDescriptor
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.http.command.JakartaCommandValidator
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A real annotation instance for the constraint descriptor. */
private class Constrained(@field:NotBlank val sku: String)

/** The command routes of the public port (roadmap 5b.2): routes, headers, results and problems. */
class CommandRoutesTest {
    private val node = HttpNode()

    private fun Answer.assertSucceeded(status: Int = 200): Answer {
        this.status.assert().isEqualTo(status)
        contentType.assert().isEqualTo("application/json")
        body!!["status"].asString().assert().isEqualTo("SUCCEEDED")
        return this
    }

    private fun Answer.assertProblem(status: Int, code: String?, withResult: Boolean): Answer {
        this.status.assert().isEqualTo(status)
        contentType.assert().isEqualTo("application/problem+json")
        val problem = body!!
        problem["status"].asInt().assert().isEqualTo(status)
        problem["title"].asString().assert().isNotBlank()
        if (code == null) {
            problem["type"].asString().assert().isEqualTo("about:blank")
            problem.has("code").assert().isFalse()
        } else {
            problem["type"].asString().assert().isEqualTo("urn:wow:error:$code")
            problem["code"].asString().assert().isEqualTo(code)
        }
        problem.has("result").assert().isEqualTo(withResult)
        if (withResult) {
            problem["result"]["error"]["code"].asString().assert().isEqualTo(code)
        }
        return this
    }

    @Test
    fun `a creation route without an id sends the command to a new aggregate`() {
        val answer = node.post("/shop/cart/open-cart", """{"note":"hi"}""").assertSucceeded()
        val result = answer.body!!
        UUID.fromString(result["ref"]["id"].asString()).version().assert().isEqualTo(7)
        result["ref"]["tenantId"].asString().assert().isEqualTo(TENANT)
        result["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        result["version"].asInt().assert().isEqualTo(1)
        result["results"]["opened"].asString().assert().isEqualTo("hi")
    }

    @Test
    fun `a route with an id sends the command to that aggregate`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertSucceeded()
        val second = node.post("/shop/cart/c-1/add-item", """{"sku":"pear","quantity":2}""").assertSucceeded()
        second.body!!["ref"]["id"].asString().assert().isEqualTo("c-1")
        second.body!!["version"].asInt().assert().isEqualTo(2)
    }

    @Test
    fun `an empty body is an empty object`() {
        node.post("/shop/cart/open-cart", body = null).assertSucceeded()
    }

    @Test
    fun `Wow-Request-Id is the request id, and a retry with it is a duplicate`() {
        val headers = mapOf(WowHeaders.REQUEST_ID to "req-1")
        val first = node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = headers).assertSucceeded()
        first.body!!["requestId"].asString().assert().isEqualTo("req-1")
        val retry = node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = headers).assertSucceeded()
        retry.body!!["duplicate"].asBoolean().assert().isTrue()
        retry.body!!["version"].asInt().assert().isEqualTo(1)
    }

    @Test
    fun `a blank Wow-Request-Id is malformed`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.REQUEST_ID to " "))
            .assertProblem(400, FrameworkCodes.MALFORMED, withResult = true)
    }

    @Test
    fun `Wow-Wait accepted is answered 202 at once`() {
        val answer = node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf(WowHeaders.WAIT to "accepted"),
        ).assertSucceeded(202)
        answer.body!!["hop"]["stage"].asString().assert().isEqualTo("ACCEPTED")
    }

    @Test
    fun `Wow-Wait committed waits for the commit`() {
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf(WowHeaders.WAIT to "committed(shop)")
        )
            .assertSucceeded().body!!["hop"]["context"].asString().assert().isEqualTo(CONTEXT)
    }

    @Test
    fun `a Wow-Wait that does not parse is wow-wait-invalid without a result`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.WAIT to "soon"))
            .assertProblem(400, FrameworkCodes.WAIT_INVALID, withResult = false)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.WAIT_TIMEOUT to "-1s"))
            .assertProblem(400, FrameworkCodes.WAIT_INVALID, withResult = false)
    }

    @Test
    fun `a Wow-Wait the runtime cannot follow is wow-wait-invalid with its result`() {
        // No snapshot subscription here: an explicit snapshot hop is refused before the command runs.
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.WAIT to "snapshot"))
            .assertProblem(400, FrameworkCodes.WAIT_INVALID, withResult = true)
    }

    @Test
    fun `a wait that runs out of time is 504 with the last hop reached`() {
        val answer = node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf(WowHeaders.WAIT to "handled(shop/audit)", WowHeaders.WAIT_TIMEOUT to "200ms"),
        ).assertProblem(504, FrameworkCodes.WAIT_TIMEOUT, withResult = true)
        answer.body!!["result"]["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        answer.body!!["result"]["status"].asString().assert().isEqualTo("FAILED")
    }

    @Test
    fun `Wow-Wait-Timeout alone bounds the default plan`() {
        val node = HttpNode()
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf(WowHeaders.WAIT_TIMEOUT to "PT1.5S")
        )
            .assertSucceeded()
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertSucceeded()
        node.commands.map { it.wait }.assert().containsExactly(WaitPlan.DEFAULT.within(1500.milliseconds), WaitPlan.DEFAULT)
    }

    @Test
    fun `a request that names no timeout waits the node's default timeout`() {
        val node = HttpNode(defaultWaitTimeout = 4.seconds)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertSucceeded()
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.WAIT to "committed"))
            .assertSucceeded()
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf(WowHeaders.WAIT to "committed", WowHeaders.WAIT_TIMEOUT to "2s"),
        ).assertSucceeded()
        node.commands.map { it.wait }.assert().containsExactly(
            WaitPlan.DEFAULT.within(4.seconds),
            WaitPlan.COMMITTED.within(4.seconds),
            WaitPlan.COMMITTED.within(2.seconds),
        )
    }

    @Test
    fun `If-Match sets the expected version, and a mismatch is 412`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertSucceeded()
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.IF_MATCH to "\"1\""))
            .assertSucceeded().body!!["version"].asInt().assert().isEqualTo(2)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.IF_MATCH to "\"1\""))
            .assertProblem(412, FrameworkCodes.VERSION_MISMATCH, withResult = true)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.IF_MATCH to "*"))
            .assertSucceeded()
    }

    @Test
    fun `an If-Match that is not a version is malformed without a result`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.IF_MATCH to "\"abc\""))
            .assertProblem(400, FrameworkCodes.MALFORMED, withResult = false)
    }

    @Test
    fun `Wow-Scope without the cross-scope permission is forbidden, never ignored`() {
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf(WowHeaders.SCOPE to """owner="bob""""),
        ).assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN, withResult = true)
    }

    @Test
    fun `Wow-Scope lets a cross-scope caller act in another scope`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", user = "alice").assertSucceeded()
        val answer = node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            user = "admin",
            headers = mapOf("X-Admin" to "true", WowHeaders.SCOPE to """tenant="$TENANT", owner="alice""""),
        ).assertSucceeded()
        answer.body!!["version"].asInt().assert().isEqualTo(2)
        // Another tenant is another aggregate.
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            user = "admin",
            headers = mapOf("X-Admin" to "true", WowHeaders.SCOPE to "tenant=other"),
        ).assertSucceeded().body!!["ref"]["tenantId"].asString().assert().isEqualTo("other")
    }

    @Test
    fun `a Wow-Scope that does not parse is malformed without a result`() {
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf(WowHeaders.SCOPE to "region=\"eu\""),
        ).assertProblem(400, FrameworkCodes.MALFORMED, withResult = false)
    }

    @Test
    fun `Wow-Space selects one of the caller's spaces`() {
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf("X-Spaces" to "home,work", WowHeaders.SPACE to "work"),
        ).assertSucceeded()
        // The kernel's scope guard: the aggregate is in the space work now.
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf("X-Spaces" to "home,work", WowHeaders.SPACE to "home"),
        ).assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN, withResult = true)
    }

    @Test
    fun `a Wow-Space the caller is not in is forbidden without a result`() {
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf("X-Spaces" to "home", WowHeaders.SPACE to "work"),
        ).assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN, withResult = false)
    }

    @Test
    fun `only the listed request facts reach the command`() {
        val node = HttpNode(
            options = WowHttpOptions(requestFacts = setOf("X-App-Id", "X-Device-Id")),
        )
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf("X-App-Id" to "app-1", "X-Other" to "ignored"),
        ).assertSucceeded()
        node.commands.map { it.headers }.assert().containsExactly(mapOf("X-App-Id" to "app-1"))
    }

    @Test
    fun `a request without credentials is 401 when a resolver is configured`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", user = null)
            .assertProblem(401, FrameworkCodes.UNAUTHENTICATED, withResult = false)
    }

    @Test
    fun `the default resolver serves the anonymous caller`() {
        val node = HttpNode(callers = me.ahoo.wow.http.caller.CallerResolver.ANONYMOUS)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", user = null).assertSucceeded()
            .body!!["ref"]["tenantId"].asString().assert().isEqualTo("(0)")
    }

    @Test
    fun `another owner's aggregate is forbidden`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", user = "alice").assertSucceeded()
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", user = "bob")
            .assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN, withResult = true)
    }

    @Test
    fun `a missing aggregate is 404 with its result, a missing route 404 without one`() {
        node.post("/shop/cart/nobody/checkout").assertProblem(404, FrameworkCodes.NOT_FOUND, withResult = true)
        node.post("/shop/cart/c-1/unknown-command").assertProblem(404, code = null, withResult = false)
        node.post("/shop/basket/c-1/add-item").assertProblem(404, code = null, withResult = false)
        node.get("/shop/cart/c-1/add-item").assertProblem(404, code = null, withResult = false)
    }

    @Test
    fun `an aggregate that exists is 409 for a command that creates it`() {
        node.post("/shop/cart/c-1/open-cart").assertSucceeded()
        node.post("/shop/cart/c-1/open-cart").assertProblem(409, FrameworkCodes.EXISTS, withResult = true)
    }

    @Test
    fun `a domain rejection takes its context's status, else 422, unless the host configures one`() {
        node.post("/shop/cart/c-1/open-cart").assertSucceeded()
        node.post("/shop/cart/c-1/checkout").assertProblem(409, CART_EMPTY, withResult = true)
            .body!!["title"].asString().assert().isEqualTo("The cart has no item.")
        node.post("/shop/cart/c-1/checkout", """{"note":"closed"}""")
            .assertProblem(422, CART_CLOSED, withResult = true)
            .body!!["detail"].asString().assert().isEqualTo("The cart is closed.")
        val configured =
            HttpNode(options = WowHttpOptions(errorStatuses = mapOf(CART_CLOSED to 451, CART_EMPTY to 400)))
        configured.post("/shop/cart/c-1/open-cart").assertSucceeded()
        configured.post("/shop/cart/c-1/checkout", """{"note":"closed"}""")
            .assertProblem(451, CART_CLOSED, withResult = true)
        configured.post("/shop/cart/c-1/checkout").assertProblem(400, CART_EMPTY, withResult = true)
    }

    @Test
    fun `a body that is not the command is malformed`() {
        node.post("/shop/cart/c-1/add-item", """{"quantity":1}""")
            .assertProblem(400, FrameworkCodes.MALFORMED, withResult = true)
        node.post("/shop/cart/c-1/add-item", """{"sku":""")
            .assertProblem(400, FrameworkCodes.MALFORMED, withResult = true)
    }

    @Test
    fun `Jakarta violations are wow-command-invalid with the violations`() {
        val path = mockk<Path> { every { this@mockk.toString() } returns "sku" }
        val notBlank = Constrained::class.java.getDeclaredField("sku").getAnnotation(NotBlank::class.java)
        val descriptor = mockk<ConstraintDescriptor<NotBlank>> { every { annotation } returns notBlank }
        val violation = mockk<ConstraintViolation<Any>> {
            every { propertyPath } returns path
            every { constraintDescriptor } returns descriptor
            every { message } returns "must not be blank"
        }
        val validator = mockk<Validator> {
            every { validate(any<Any>()) } answers {
                val body = firstArg<Any>()
                if (body is AddItem && body.sku.isBlank()) setOf(violation) else emptySet()
            }
        }
        val node = HttpNode(validators = listOf(JakartaCommandValidator(validator)))
        val problem = node.post("/shop/cart/c-1/add-item", """{"sku":" "}""")
            .assertProblem(400, FrameworkCodes.INVALID, withResult = true).body!!
        problem["violations"].size().assert().isEqualTo(1)
        problem["violations"][0]["path"].asString().assert().isEqualTo("sku")
        problem["violations"][0]["code"].asString().assert().isEqualTo("NotBlank")
        problem["violations"][0]["message"].asString().assert().isEqualTo("must not be blank")
        problem["result"]["error"]["violations"].size().assert().isEqualTo(1)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertSucceeded()
    }

    @Test
    fun `DELETE deletes the aggregate and the lifecycle routes restore and transfer it`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertSucceeded()
        node.client.delete().uri("/shop/cart/c-1").header("X-User", "alice").answer().assertSucceeded()
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""")
            .assertProblem(410, FrameworkCodes.DELETED, withResult = true)
        node.post("/shop/cart/c-1/wow.lifecycle.restore-aggregate", body = null).assertSucceeded()
        node.post("/shop/cart/c-1/wow.lifecycle.apply-tags", """{"tags":{"tier":["gold"]}}""").assertSucceeded()
        node.post("/shop/cart/c-1/wow.lifecycle.transfer-owner", """{"ownerId":"bob"}""").assertSucceeded()
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", user = "bob").assertSucceeded()
    }

    @Test
    fun `only the lifecycle commands the Manifest lists have routes`() {
        val node = HttpNode(manifest = cartManifest(lifecycle = listOf(Lifecycle.DELETE_AGGREGATE)))
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertSucceeded()
        node.post("/shop/cart/c-1/wow.lifecycle.restore-aggregate", body = null)
            .assertProblem(404, code = null, withResult = false)
        node.client.delete().uri("/shop/cart/c-1").header("X-User", "alice").answer().assertSucceeded()
        val none = HttpNode(manifest = cartManifest(lifecycle = emptyList()))
        none.client.delete().uri("/shop/cart/c-1").header("X-User", "alice").answer()
            .assertProblem(404, code = null, withResult = false)
    }

    @Test
    fun `a body of another type than JSON is 415, an Accept without JSON 406`() {
        node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice")
            .header("Content-Type", "text/plain").bodyValue("apple").answer()
            .assertProblem(415, code = null, withResult = false)
        node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice")
            .header("Content-Type", "application/vnd.shop+json").bodyValue("""{"sku":"apple"}""").answer()
            .assertSucceeded()
        node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice").header("Accept", "text/html")
            .header("Content-Type", "application/json").bodyValue("""{"sku":"apple"}""").answer()
            .assertProblem(406, code = null, withResult = false)
    }
}
