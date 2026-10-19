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

package me.ahoo.wow.ops.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerAggregate
import me.ahoo.wow.api.ledger.LedgerEntry
import me.ahoo.wow.api.ledger.LedgerRetrySpec
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.ops.api.parked.ParkedStatus
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The ops service in its production shape, the `cosec` profile (roadmap P.15), end to end over HTTP: CoSec verifies
 * each request's token, and the operator is the token's principal. A request without a token, or with one CoSec did
 * not sign, is answered 401; a viewer reads the entries and may not act on them; an operator's resume goes to the
 * owning context under the operator's name.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsCoSecTest {
    private val ops: OpsNode = startOps(
        OpsConfiguration.TRUST_IDENTITY_HEADERS to false,
        "cosec.jwt.secret" to SECRET,
        profiles = listOf("cosec"),
    )
    private val tokens: TokenConverter = ops.context.getBean(TokenConverter::class.java)

    @AfterAll
    fun closeOps() {
        ops.close()
    }

    private fun signedIn(id: String, vararg roles: String): Who {
        val principal: CoSecPrincipal = SimpleTenantPrincipal(
            SimplePrincipal(id, roles = roles.toSet()),
            SimpleTenant(DEFAULT_TENANT),
        )
        return bearer(tokens.toToken(principal).accessToken)
    }

    private fun bearer(token: String): Who = Who(mapOf(AUTHORIZATION to "Bearer $token"))

    private fun entry(context: String, id: String): LedgerEntry = WowJson.mapper.convertValue(
        // A ledger entry is a wire record whose constructor is Wow's own (decision D216): read it from its JSON.
        mapOf(
            "service" to "$context-service",
            "context" to context,
            "subscription" to SAGA,
            "aggregate" to LedgerAggregate.of(context, "payment", Caller.DEFAULT_TENANT, id),
            "fromVersion" to 2,
            "fromPosition" to null,
            "reason" to "The gateway is unreachable.",
            "failedAt" to FAILED_AT,
            "retries" to 3,
            "retrySpec" to LedgerRetrySpec.of(3, "PT1M", "PT2M"),
            "status" to ParkedStatus.EXHAUSTED,
            "recordedAt" to FAILED_AT + 1,
        ),
        LedgerEntry::class.java,
    )

    @Test
    fun `the caller should be CoSec's`() {
        ops.context.getBean(CallerResolver::class.java).assert().isInstanceOf(CoSecCallerResolver::class.java)
    }

    @Test
    fun `the deployment's probes should answer without a token (R3-H)`() {
        eventually("ready") { ops.get("/actuator/health/readiness", Who.NOBODY).status == OK }
        ops.get("/actuator/health/liveness", Who.NOBODY).status.assert().isEqualTo(OK)
    }

    @Test
    fun `a signed-in operator should read an entry and resume it, which goes to the owning context in its name`() {
        val id = ops.publish(entry("shop-resume", "p-1"))
        val operator = signedIn("alice", OpsCallers.OPERATOR)
        eventually("$id recorded") { ops.get("/ops/parked/$id/state", operator).status == OK }
        ops.get("/ops/parked/$id/state", operator).assertOk().json["state"]["status"].asString().assert()
            .isEqualTo(ParkedStatus.EXHAUSTED)

        ops.post("/ops/parked/$id/resume", """{"reason":"The gateway is back."}""", operator).assertOk()
        eventually("the resume sent to shop-resume") { ops.ledgerCommands("shop-resume").isNotEmpty() }
        val resume = ops.ledgerCommands("shop-resume").single()
        resume.type.assert().isEqualTo(Ledger.RESUME)
        resume.operator!!.id.assert().isEqualTo("alice")
    }

    @Test
    fun `a viewer should read the entries and may not act on them`() {
        val id = ops.publish(entry("shop-viewer", "p-1"))
        val viewer = signedIn("victor", "ops.viewer")
        eventually("$id recorded") { ops.get("/ops/parked/$id/state", viewer).status == OK }
        ops.post("/ops/parked/$id/skip", "{}", viewer).assertProblem(FORBIDDEN, "wow.auth.forbidden")
    }

    @Test
    fun `a request without a token, or with a token CoSec did not sign, should be answered 401`() {
        val id = ops.publish(entry("shop-anonymous", "p-1"))
        val operator = signedIn("alice", OpsCallers.OPERATOR)
        eventually("$id recorded") { ops.get("/ops/parked/$id/state", operator).status == OK }
        ops.get("/ops/parked/$id/state", Who.NOBODY).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        ops.post("/ops/parked/$id/resume", "{}", Who.NOBODY).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        val forged = JWT.create()
            .withJWTId("forged")
            .withSubject("mallory")
            .withClaim("roles", listOf(OpsCallers.OPERATOR))
            .withExpiresAt(java.time.Instant.now().plusSeconds(600))
            .sign(Algorithm.HMAC256("not-the-service-secret-of-32-characters"))
        ops.post("/ops/parked/$id/resume", "{}", bearer(forged)).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        // Nor do the identity headers of a local run name anyone here.
        ops.post("/ops/parked/$id/resume", "{}", Who.OPERATOR).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        ops.ledgerCommands("shop-anonymous").assert().isEmpty()
    }

    private companion object {
        const val SECRET = "the-ops-service-test-secret-of-32-characters"
        const val AUTHORIZATION = "Authorization"
        const val DEFAULT_TENANT = "(0)"
        const val SAGA = "PaymentSaga.onOrderPlaced"
        const val FAILED_AT = 1_700_000_000_000L
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}
