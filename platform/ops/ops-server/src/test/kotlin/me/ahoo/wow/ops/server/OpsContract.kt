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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerAggregate
import me.ahoo.wow.api.ledger.LedgerEntry
import me.ahoo.wow.api.ledger.LedgerResolution
import me.ahoo.wow.api.ledger.LedgerRetrySpec
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.ops.api.parked.ParkedStatus
import me.ahoo.wow.ops.domain.parked.ParkedRejections
import me.ahoo.wow.ops.domain.parked.ParkedState
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * The ops service end to end over HTTP (roadmap 7.1a, decision D141): an owning service's ledger publishes its entries
 * to `wow.ops.commands`, an operator finds them through the snapshot query routes and acts on them, and each action
 * goes back to the owning context's command topic as the ledger's system command. Each test works on a context of its
 * own, so the tests share the server without seeing each other's entries. It runs on the memory backend
 * ([OpsMemoryTest]) and on PostgreSQL (`OpsPostgresTest`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OpsContract {
    /** The ops server the requests go to. */
    protected abstract val ops: OpsNode

    @AfterAll
    fun closeOps() {
        ops.close()
    }

    private fun context(): String = "shop-${UUID.randomUUID().toString().take(8)}"

    private fun entry(
        context: String,
        id: String,
        status: String = ParkedStatus.EXHAUSTED,
        subscription: String = SAGA,
        failedAt: Long = FAILED_AT,
        reason: String = "The gateway is unreachable.",
    ): LedgerEntry = WowJson.mapper.convertValue(
        // A ledger entry is a wire record whose constructor is Wow's own (decision D216): read it from its JSON.
        mapOf(
            "service" to "$context-service",
            "context" to context,
            "subscription" to subscription,
            "aggregate" to LedgerAggregate(context, "payment", Caller.DEFAULT_TENANT, id),
            "fromVersion" to 2,
            "fromPosition" to null,
            "reason" to reason,
            "failedAt" to failedAt,
            "retries" to 3,
            "retrySpec" to LedgerRetrySpec.of(3, "PT1M", "PT2M"),
            "status" to status,
            "recordedAt" to failedAt + 1,
        ),
        LedgerEntry::class.java,
    )

    /** The snapshot of entry [id], or null while ops has none. */
    private fun state(id: String, who: Who = Who.OPERATOR): tools.jackson.databind.JsonNode? {
        val answer = ops.get("/ops/parked/$id/state", who)
        if (answer.status == NOT_FOUND) return null
        return answer.assertOk().json["state"]
    }

    private fun query(filter: String, who: Who = Who.OPERATOR): Answer =
        ops.post(QUERY, """{"kind":"list","filter":$filter,"sort":[{"field":"aggregateId","direction":"ASC"}]}""", who)

    private fun recorded(context: String, vararg ids: String) {
        val wanted = ids.toSet()
        eventually("$wanted listed") {
            query(eq("state.context", context)).assertOk().ids.toSet() == wanted
        }
    }

    @Test
    fun `an operator should list the entries the services publish, and filter them by context, subscription, status and time`() {
        val context = context()
        val exhausted = ops.publish(entry(context, "p-1"))
        val scheduled = ops.publish(entry(context, "p-2", status = ParkedStatus.SCHEDULED, failedAt = FAILED_AT + HOUR))
        val other = ops.publish(
            entry(context, "p-3", subscription = "OtherSaga.onEvent", failedAt = FAILED_AT + 2 * HOUR)
        )
        recorded(context, exhausted, scheduled, other)

        val byStatus = and(eq("state.context", context), eq("state.status", ParkedStatus.EXHAUSTED))
        query(byStatus).assertOk().ids.assert().containsExactlyInAnyOrder(exhausted, other)
        query(and(eq("state.context", context), eq("state.subscription", SAGA))).assertOk().ids.assert()
            .containsExactlyInAnyOrder(exhausted, scheduled)
        val since = """{"op":"GTE","field":"state.failedAt","value":${FAILED_AT + HOUR}}"""
        query(and(eq("state.context", context), since)).assertOk().ids.assert()
            .containsExactlyInAnyOrder(scheduled, other)

        // The detail: the entry as its ledger published it.
        val detail = state(exhausted)!!
        detail["service"].asString().assert().isEqualTo("$context-service")
        detail["subscription"].asString().assert().isEqualTo(SAGA)
        detail["aggregate"]["id"].asString().assert().isEqualTo("p-1")
        detail["reason"].asString().assert().contains("unreachable")
        detail["retrySpec"]["maxRetries"].asInt().assert().isEqualTo(3)
        // A caller of the tenant reads the entries too; one of another tenant sees none of them.
        query(eq("state.context", context), Who.VIEWER).assertOk().ids.assert().hasSize(3)
        query(eq("state.context", context), Who.OUTSIDER).assertOk().ids.assert().isEmpty()
    }

    @Test
    fun `an operator's resume should go back to the owning context as the ledger's command, and the entry resolve`() {
        val context = context()
        val id = ops.publish(entry(context, "p-1"))
        eventually("$id recorded") { state(id) != null }

        ops.post("/ops/parked/$id/resume", """{"reason":"The gateway is back."}""", Who.OPERATOR).assertOk()
        state(id)!!["requested"].asString().assert().isEqualTo(ParkedState.RESUME)
        eventually("the resume sent to $context") { ops.ledgerCommands(context).isNotEmpty() }
        val resume = ops.ledgerCommands(context).single()
        resume.type.assert().isEqualTo(Ledger.RESUME)
        resume.ref.assert().isEqualTo(Ledger.targetOf(context, id))
        resume.operator!!.id.assert().isEqualTo("alice")
        WowJson.mapper.treeToValue(resume.body, ResumeParked::class.java).assert()
            .isEqualTo(ResumeParked(SAGA, LedgerAggregate(context, "payment", Caller.DEFAULT_TENANT, "p-1")))

        // The owning service resumed it and publishes that it is resolved.
        ops.resolve(
            LedgerResolution.of(
                "$context-service",
                context,
                SAGA,
                LedgerAggregate(context, "payment", Caller.DEFAULT_TENANT, "p-1"),
                FAILED_AT,
                LedgerResolution.RESUMED,
                FAILED_AT + HOUR,
            ),
        )
        eventually("$id resolved") { state(id)!!["status"].asString() == ParkedStatus.RESOLVED }
        state(id)!!["outcome"].asString().assert().isEqualTo(LedgerResolution.RESUMED)
        query(and(eq("state.context", context), eq("state.status", ParkedStatus.RESOLVED))).assertOk().ids.assert()
            .containsExactly(id)
        // Nothing is parked to act on any more.
        ops.post("/ops/parked/$id/skip", "{}", Who.OPERATOR).assertProblem(CONFLICT, ParkedRejections.RESOLVED)
    }

    @Test
    fun `skip, mark-recoverable and apply-retry-spec should go back as the ledger's commands of the same name`() {
        val context = context()
        val id = ops.publish(entry(context, "p-1"))
        eventually("$id recorded") { state(id) != null }
        val aggregate = LedgerAggregate(context, "payment", Caller.DEFAULT_TENANT, "p-1")

        ops.post("/ops/parked/$id/mark-recoverable", """{"recoverable":"unrecoverable"}""", Who.OPERATOR).assertOk()
        ops.post("/ops/parked/$id/apply-retry-spec", """{"maxRetries":5,"minBackoff":"1m"}""", Who.OPERATOR).assertOk()
        ops.post("/ops/parked/$id/skip", """{"reason":"Refunded by hand."}""", Who.OPERATOR).assertOk()
        eventually("three commands sent to $context") { ops.ledgerCommands(context).size == 3 }
        val sent = ops.ledgerCommands(context).associateBy { it.type }
        WowJson.mapper.treeToValue(sent.getValue(Ledger.MARK_RECOVERABLE).body, MarkParkedRecoverable::class.java)
            .assert().isEqualTo(MarkParkedRecoverable(SAGA, aggregate, "UNRECOVERABLE"))
        WowJson.mapper.treeToValue(sent.getValue(Ledger.APPLY_RETRY_SPEC).body, ApplyParkedRetrySpec::class.java)
            .assert().isEqualTo(ApplyParkedRetrySpec(SAGA, aggregate, maxRetries = 5, minBackoff = "1m"))
        WowJson.mapper.treeToValue(sent.getValue(Ledger.SKIP).body, SkipParked::class.java)
            .assert().isEqualTo(SkipParked(SAGA, aggregate))

        // An invalid argument is the entry's rejection, and sends nothing.
        ops.post("/ops/parked/$id/mark-recoverable", """{"recoverable":"maybe"}""", Who.OPERATOR)
            .assertProblem(UNPROCESSABLE, ParkedRejections.INVALID)
    }

    @Test
    fun `an operator should count the entries by error type and subscription, and read an entry's history`() {
        val context = context()
        val connect = "java.net.ConnectException: Connection refused"
        val first = ops.publish(entry(context, "p-1", reason = "$connect: gateway-1"))
        val second = ops.publish(entry(context, "p-2", reason = "$connect: gateway-2"))
        val other = ops.publish(
            entry(
                context,
                "p-3",
                subscription = "OtherSaga.onEvent",
                reason = "java.lang.IllegalStateException: closed"
            )
        )
        recorded(context, first, second, other)

        // The failure clusters: the entries counted by what failed and where.
        val clusters = ops.post(
            QUERY,
            """{"kind":"aggregate","filter":${eq("state.context", context)},"groupBy":[""" +
                """{"type":"TERMS","field":"state.errorType","alias":"errorType"},""" +
                """{"type":"TERMS","field":"state.subscription","alias":"subscription"}],""" +
                """"metrics":[{"type":"COUNT","alias":"count"}],"sort":[{"field":"count","direction":"DESC"}]}""",
            Who.OPERATOR,
        ).assertOk().json.values().map {
            Triple(it["errorType"].asString(), it["subscription"].asString(), it["count"].asLong())
        }
        clusters.assert().containsExactly(
            Triple("java.net.ConnectException", SAGA, 2L),
            Triple("java.lang.IllegalStateException", "OtherSaga.onEvent", 1L),
        )

        // The history: the entry's own event streams, oldest first.
        ops.post("/ops/parked/$first/resume", """{"reason":"The gateway is back."}""", Who.OPERATOR).assertOk()
        val history = ops.post(
            "/ops/parked/events/query",
            """{"kind":"list","filter":${eq("aggregateId", first)}}""",
            Who.OPERATOR,
        ).assertOk().json.values().map { stream -> stream["events"].values().single()["name"].asString() }
        history.assert().containsExactly("ops.parked.ParkedRecorded", "ops.parked.ResumeRequested")
    }

    @Test
    fun `only an operator should act, and nobody should forge a record over HTTP`() {
        val context = context()
        val id = ops.publish(entry(context, "p-1"))
        eventually("$id recorded") { state(id) != null }

        ops.get("/ops/parked/$id/state", Who.NOBODY).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        query(eq("state.context", context), Who.NOBODY).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        ops.post("/ops/parked/$id/resume", "{}", Who.NOBODY).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        ops.post("/ops/parked/$id/resume", "{}", Who.VIEWER).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        ops.post("/ops/parked/$id/skip", "{}", Who.VIEWER).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        val forged = """{"resolution":{"service":"$context-service","context":"$context","subscription":"$SAGA",
            "aggregate":{"context":"$context","name":"payment","tenantId":"(0)","id":"p-1"},
            "outcome":"RESOLVED","resolvedAt":1}}"""
        ops.post("/ops/parked/$id/record-resolved", forged, Who.OPERATOR).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        state(id)!!["status"].asString().assert().isEqualTo(ParkedStatus.EXHAUSTED)
        ops.ledgerCommands(context).assert().isEmpty()
    }

    @Test
    fun `the node's own parked entries should be an operator's to read`() {
        ops.get("/wow/ops/parked", Who.OPERATOR).assertOk().json["entries"].isArray.assert().isTrue()
    }

    private fun eq(field: String, value: String): String = """{"op":"EQ","field":"$field","value":"$value"}"""

    private fun and(vararg filters: String): String = """{"op":"AND","operands":[${filters.joinToString(",")}]}"""

    companion object {
        const val QUERY = "/ops/parked/snapshot/query"
        const val SAGA = "PaymentGatewaySaga.onPaymentRequested"
        const val FAILED_AT = 1_760_000_000_000L
        const val HOUR = 3_600_000L
        const val UNPROCESSABLE = 422
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
    }
}
