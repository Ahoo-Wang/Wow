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

package me.ahoo.wow.example.eshop.e2e.cluster

import me.ahoo.wow.example.eshop.e2e.json
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Decision D328 as deployed: a command sent without a `Wow-Request-Id` takes the `CoSec-Request-Id` the gateway set
 * as its request id, which holds across the gateway's retry only if the retry resends that same id.
 *
 * - [acrossRetry]: requests through the gateway to the flaky upstream (`gateway/flaky.yaml`), which resets each
 *   probe's first connection after reading it whole and answers its retry. Both attempts must reach it with the same
 *   non-blank `CoSec-Request-Id` and the same body, and none with a `Wow-Request-Id` the client did not send.
 * - [fallback]: a basket command without `Wow-Request-Id` commits with a request id that is not its command id (the
 *   default without any header), and its trace records that id.
 */
class RequestIds(private val kube: Kube, private val flows: ShopFlows) {
    fun acrossRetry(): String {
        val probes = (1..PROBES).map { "probe-${UUID.randomUUID()}" }
        probes.forEach { probe ->
            val answer = flows.shop.post(
                "/ga-e2e-flaky/probe",
                """{"probe":"$probe"}""",
                Who.buyer("rita"),
                mapOf(PROBE to probe)
            )
            check(answer.status == ShopFlows.OK) {
                "the gateway did not retry $probe's reset request: ${answer.status} ${answer.body.take(ShopFlows.BODY)}"
            }
        }
        val records = kube.require("logs", "deployment/flaky").lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .map { json.readTree(it) }
            .filter { it["probe"]?.asString() in probes }
            .groupBy { it["probe"].asString() }
        val ids = probes.map { probe ->
            val attempts = records[probe].orEmpty()
            check(attempts.map { it["attempt"].asInt() } == listOf(1, 2)) {
                "$probe reached the flaky upstream ${attempts.size} times, not once and once retried: $attempts"
            }
            val (first, retry) = attempts
            val id = first.text("cosecRequestId")
            check(id.isNotBlank()) { "the gateway sent $probe without a CoSec-Request-Id: $first" }
            check(retry.text("cosecRequestId") == id) {
                "D328 does not hold: the gateway's retry of $probe carried CoSec-Request-Id ${retry.text("cosecRequestId")}, " +
                    "the first attempt $id"
            }
            check(retry.text("body") == first.text("body")) { "the retry of $probe resent another body: $attempts" }
            check(attempts.all { it.text("wowRequestId").isBlank() }) {
                "the gateway added a Wow-Request-Id to $probe: $attempts"
            }
            id
        }
        return "${probes.size} requests reset after reading, each retried once by the gateway with the same " +
            "CoSec-Request-Id and body (e.g. ${ids.first()}), no Wow-Request-Id added"
    }

    fun fallback(): String {
        val answer = flows.addItem(BUYER, "mug", flows.price("mug"), 1)
        check(answer.status == ShopFlows.OK) {
            "add-basket-item answered ${answer.status}: ${answer.body.take(ShopFlows.BODY)}"
        }
        val result = json.readTree(answer.body)
        val requestId = result.text("requestId")
        val commandId = result.text("commandId")
        check(requestId.isNotBlank() && requestId != commandId) {
            "without Wow-Request-Id the command's request id is $requestId, its command id $commandId: the " +
                "CoSec-Request-Id was not taken (D328)"
        }
        val traced = flows.trace("/eshop-basket/basket/$BUYER").last().requestId
        check(
            traced == requestId
        ) { "the basket's last commit has request id $traced, the command answered $requestId" }
        return "a command without Wow-Request-Id committed with request id $requestId (the gateway's " +
            "CoSec-Request-Id), not its command id $commandId"
    }

    private fun JsonNode.text(field: String): String = get(field)?.takeUnless { it.isNull }?.asString().orEmpty()

    private companion object {
        const val PROBE = "X-Ga-Probe"
        const val PROBES = 5
        const val BUYER = "rita"
    }
}
