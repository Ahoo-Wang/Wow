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

import me.ahoo.cosec.runtime.identity.IdentityCodec
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.ops.api.parked.EntryAggregate
import me.ahoo.wow.ops.api.parked.EntryRetrySpec
import me.ahoo.wow.ops.api.parked.ParkedEntry
import me.ahoo.wow.ops.api.parked.ParkedStatus
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant

/**
 * The ops service in its production shape, the `cosec` profile (roadmap P.15), end to end over HTTP: behind the CoSec
 * gateway, which verified each request's credential and forwards the identity it verified in `CoSec-Identity`, CoSec's
 * gateway mode reads it and the operator is its principal. A request without an identity is answered 401; a viewer
 * reads the entries and may not act on them; an operator's retry goes to the owning context under the operator's name.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsCoSecTest {
    private val ops: OpsNode = startOps(
        profiles = listOf("cosec"),
    )

    @AfterAll
    fun closeOps() {
        ops.close()
    }

    /**
     * [id] of CoSec's default tenant with [roles], as the CoSec gateway forwards it after verifying its credential: the
     * service holds no secret and verifies nothing, it believes the header.
     */
    private fun signedIn(id: String, vararg roles: String): Who {
        val now = Instant.now()
        val identity = Identity(
            tenant = DEFAULT_TENANT,
            subject = id,
            app = "",
            space = "",
            roles = roles.toSet(),
            credential = Identity.Credential(Identity.Method.JWT, "token-of-$id", now + Duration.ofHours(1)),
            issuedAt = now,
            requestId = "request-of-$id",
        )
        return Who(mapOf(IdentityCodec.HEADER to IdentityCodec.encode(identity)))
    }

    private fun entry(context: String, id: String): ParkedEntry = WowJson.mapper.convertValue(
        // A ledger entry is a wire record whose constructor is Wow's own (decision D216): read it from its JSON.
        mapOf(
            "service" to "$context-service",
            "context" to context,
            "subscription" to SAGA,
            "aggregate" to EntryAggregate(context, "payment", Caller.DEFAULT_TENANT, id),
            "fromVersion" to 2,
            "fromPosition" to null,
            "reason" to "The gateway is unreachable.",
            "failedAt" to FAILED_AT,
            "retries" to 3,
            "retrySpec" to EntryRetrySpec(3, "PT1M", "PT2M"),
            "status" to ParkedStatus.EXHAUSTED,
            "recordedAt" to FAILED_AT + 1,
        ),
        ParkedEntry::class.java,
    )

    @Test
    @OptIn(ExperimentalWowApi::class) // wow-cosec is experimental in 10.0
    fun `the caller should be CoSec's`() {
        ops.context.getBean(CallerResolver::class.java).assert().isInstanceOf(CoSecCallerResolver::class.java)
    }

    @Test
    fun `the deployment's probes should answer without an identity (R3-H)`() {
        eventually("ready") { ops.get("/actuator/health/readiness", Who.NOBODY).status == OK }
        ops.get("/actuator/health/liveness", Who.NOBODY).status.assert().isEqualTo(OK)
    }

    @Test
    fun `a signed-in operator should read an entry and retry it, which goes to the owning context in its name`() {
        val id = ops.publish(entry("shop-retry", "p-1"))
        val operator = signedIn("alice", OpsRoles.OPERATOR)
        eventually("$id recorded") { ops.get("/ops/parked/$id/state", operator).status == OK }
        ops.get("/ops/parked/$id/state", operator).assertOk().json["state"]["status"].asString().assert()
            .isEqualTo(ParkedStatus.EXHAUSTED)

        ops.post("/ops/parked/$id/retry", """{"reason":"The gateway is back."}""", operator).assertOk()
        eventually("the retry sent to shop-retry") { ops.ledgerCommands("shop-retry").isNotEmpty() }
        val retry = ops.ledgerCommands("shop-retry").single()
        retry.type.assert().isEqualTo(Ledger.RETRY)
        retry.operator!!.id.assert().isEqualTo("alice")
    }

    @Test
    fun `a viewer should read the entries and may not act on them`() {
        val id = ops.publish(entry("shop-viewer", "p-1"))
        val viewer = signedIn("victor", OpsRoles.VIEWER)
        eventually("$id recorded") { ops.get("/ops/parked/$id/state", viewer).status == OK }
        ops.post("/ops/parked/$id/skip", "{}", viewer).assertProblem(FORBIDDEN, "wow.auth.forbidden")
    }

    @Test
    fun `a principal with neither ops role should read nothing, refused as forbidden (UI-1)`() {
        val id = ops.publish(entry("shop-roleless", "p-1"))
        val operator = signedIn("alice", OpsRoles.OPERATOR)
        eventually("$id recorded") { ops.get("/ops/parked/$id/state", operator).status == OK }
        val everything = """{"op":"match-all"}"""
        val history = """{"kind":"list","filter":{"op":"eq","field":"aggregateId","value":"$id"}}"""
        // A principal with no role, and one with a role of another service: owner-level callers, whose owner does not
        // restrict an entry of their tenant (the entries are the tenant's, Ownership.TENANT).
        listOf(signedIn("nobody"), signedIn("stranger", "someone.else")).forEach { reader ->
            ops.post(QUERY, """{"kind":"count","filter":$everything}""", reader)
                .assertProblem(FORBIDDEN, "wow.auth.forbidden")
            ops.post(QUERY, """{"kind":"list","filter":$everything}""", reader)
                .assertProblem(FORBIDDEN, "wow.auth.forbidden")
            ops.post("/ops/parked/events/query", history, reader).assertProblem(FORBIDDEN, "wow.auth.forbidden")
            ops.get("/ops/parked/$id/state", reader).assertProblem(FORBIDDEN, "wow.auth.forbidden")
            ops.get("/ops/parked/$id/state/trace", reader).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        }
        // A viewer reads them, as the operator did.
        ops.post(QUERY, """{"kind":"count","filter":$everything}""", signedIn("victor", OpsRoles.VIEWER)).assertOk()
        ops.get("/ops/parked/$id/state", signedIn("victor", OpsRoles.VIEWER)).assertOk()
    }

    @Test
    fun `a request without an identity should be answered 401`() {
        val id = ops.publish(entry("shop-anonymous", "p-1"))
        val operator = signedIn("alice", OpsRoles.OPERATOR)
        eventually("$id recorded") { ops.get("/ops/parked/$id/state", operator).status == OK }
        ops.get("/ops/parked/$id/state", Who.NOBODY).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        ops.post("/ops/parked/$id/retry", "{}", Who.NOBODY).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        // Nor do the identity headers of a local run name anyone here.
        ops.post("/ops/parked/$id/retry", "{}", Who.OPERATOR).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        ops.ledgerCommands("shop-anonymous").assert().isEmpty()
    }

    private companion object {
        const val DEFAULT_TENANT = "(0)"
        const val QUERY = "/ops/parked/snapshot/query"
        const val SAGA = "PaymentSaga.onOrderPlaced"
        const val FAILED_AT = 1_700_000_000_000L
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}
