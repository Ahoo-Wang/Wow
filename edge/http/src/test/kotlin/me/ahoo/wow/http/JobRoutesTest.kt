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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * The routes of a context's job commands (decision D198): a tenant-level caller enables a TENANT job's instance in its
 * own tenant, with its own time zone, reschedules and disables it; a context without jobs has no such routes. They
 * are system commands of a built-in aggregate: the public port refuses them on a node without an authorizer, never
 * allowing them by default (UI-50), and the node's authorizer decides them otherwise.
 */
class JobRoutesTest {
    private val report = ScheduleJob("report", ScheduleRule.Cron("0 0 9 * * *"), JobScope.TENANT)
    private val node = HttpNode(jobs = listOf(report), authorizer = operatorsOnly)
    private val tenantLevel = mapOf("X-Tenant-Level" to "true")

    /** The three job commands of `report`: each route's path and body. */
    private val commands = listOf(
        "/shop/wow.schedule/report/enable" to """{"job":"report","rule":{"zone":"Asia/Shanghai"}}""",
        "/shop/wow.schedule/report/reschedule" to """{"rule":null}""",
        "/shop/wow.schedule/report/disable" to "{}",
    )

    @Test
    fun `an operator the authorizer allows enables, reschedules and disables the tenant's instance of a job`() {
        val enabled = node.post(commands[0].first, commands[0].second, user = OPERATOR, headers = tenantLevel)
        enabled.status.assert().withFailMessage { enabled.body.toString() }.isEqualTo(200)
        enabled.body!!["ref"]["tenantId"].asString().assert().isEqualTo(TENANT)
        node.post(commands[1].first, commands[1].second, user = OPERATOR, headers = tenantLevel)
            .status.assert().isEqualTo(200)
        node.post(commands[2].first, commands[2].second, user = OPERATOR, headers = tenantLevel)
            .status.assert().isEqualTo(200)
    }

    @Test
    fun `the authorizer refuses a job command to a buyer and to a tenant-level caller who is no operator`() {
        commands.forEach { (path, body) ->
            val buyer = node.post(path, body, user = "alice")
            buyer.status.assert().withFailMessage { buyer.body.toString() }.isEqualTo(403)
            buyer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
            buyer.body!!["detail"].asString().assert().contains("Only an operator sends wow.schedule.")
            node.post(path, body, user = "bob", headers = tenantLevel).status.assert().isEqualTo(403)
        }
    }

    @Test
    fun `without an authorizer the public port refuses every job command to every caller`() {
        val bare = HttpNode(jobs = listOf(report))
        val anonymous = HttpNode(jobs = listOf(report), callers = CallerResolver.ANONYMOUS)
        commands.forEach { (path, body) ->
            val refusals = listOf(
                anonymous.post(path, body, user = null),
                bare.post(path, body, user = "alice"),
                bare.post(path, body, user = OPERATOR, headers = tenantLevel),
            )
            refusals.forEach { refused ->
                refused.status.assert().withFailMessage { refused.body.toString() }.isEqualTo(403)
                refused.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
                refused.body!!["detail"].asString().assert().contains("has no CommandAuthorizer")
            }
        }
    }

    @Test
    fun `without an authorizer a service still sends a job command its permits list on the internal port`() {
        val bare = HttpNode(jobs = listOf(report))
        val call = CallHeaders.format(CallContext("call-1", TENANT, Scope.NONE, Causation("root-1", "ops/acme/r@1")))
        val enabled = WebTestClient
            .bindToRouterFunction(
                callRoutes(
                    bare.manifest,
                    bare.gateway,
                    DeclaredServices.resolver(TENANT),
                    permits = CallPermits(mapOf("ops-service" to ScheduleJobs.COMMANDS.keys.toList())),
                ),
            )
            .build()
            .post().uri("/shop/wow.schedule/report/enable")
            .header(WowHeaders.SERVICE, "ops-service")
            .header(WowHeaders.CALL, call)
            .header("Content-Type", "application/json")
            .bodyValue("""{"job":"report"}""")
            .answer()
        enabled.status.assert().withFailMessage { enabled.body.toString() }.isEqualTo(200)
        enabled.body!!["ref"]["id"].asString().assert().isEqualTo("report")
    }

    @Test
    fun `an unknown job is refused as an invalid job command`() {
        val answer = node.post(
            "/shop/wow.schedule/other/enable",
            """{"job":"other"}""",
            user = OPERATOR,
            headers = tenantLevel,
        )
        answer.status.assert().isEqualTo(400)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.SCHEDULE_JOB_INVALID)
    }

    @Test
    fun `a context without jobs has no job routes`() {
        HttpNode().post("/shop/wow.schedule/report/enable", """{"job":"report"}""").status.assert()
            .isEqualTo(404)
    }
}
