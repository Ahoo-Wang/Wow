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

package me.ahoo.wow.example.eshop.e2e.soak

import kotlin.math.ceil

/** The report's counts, built from the orders and their requests. */
object SoakSummary {
    /** A line of progress: orders started and how their checkouts were answered so far. */
    fun progress(orders: List<SoakOrder>): String {
        val checkouts = orders.mapNotNull { order -> order.requests.toList().firstOrNull { it.kind == "checkout" }?.final }
        val outcomes = checkouts.groupingBy { it.outcome.name }.eachCount().toSortedMap()
        return "${orders.size} orders, checkouts $outcomes"
    }

    fun counts(orders: List<SoakOrder>): Map<String, Any?> {
        val requests = orders.flatMap { order -> order.requests.toList() }
        return linkedMapOf(
            "orders" to orders.size,
            "byScenario" to orders.groupingBy { it.scenario.name }.eachCount().toSortedMap(),
            "requests" to requests.groupBy { it.kind }.mapValues { (_, list) -> requestCounts(list) }.toSortedMap(),
            "checkoutLatency" to latency(
                requests.filter { it.kind == "checkout" }.flatMap { it.attempts.toList() }
                    .filter { it.outcome == Outcome.SUCCEEDED }.map { it.millis },
            ),
            "timeoutsByHop" to requests.flatMap { it.attempts.toList() }.filter { it.outcome == Outcome.TIMEOUT }
                .groupingBy { it.hop ?: "none" }.eachCount().toSortedMap(),
            "rejectionsByCode" to requests.flatMap { it.attempts.toList() }.filter { it.outcome == Outcome.REJECTED }
                .groupingBy { it.code ?: "none" }.eachCount().toSortedMap(),
            "failuresByCode" to requests.flatMap { it.attempts.toList() }.filter { it.outcome == Outcome.FAILED }
                .groupingBy { "${it.status} ${it.code ?: ""}".trim() }.eachCount().toSortedMap(),
        )
    }

    private fun requestCounts(requests: List<SoakRequest>): Map<String, Any?> {
        val attempts = requests.flatMap { it.attempts.toList() }
        return linkedMapOf(
            "requests" to requests.size,
            "attempts" to attempts.size,
            "retried" to requests.count { it.attempts.size > 1 },
            "committed" to requests.count { it.committed },
            "unresolved" to requests.count { !it.committed && it.final?.outcome?.retried != false },
            "attemptOutcomes" to attempts.groupingBy { it.outcome.name }.eachCount().toSortedMap(),
            "finalOutcomes" to requests.groupingBy { it.final?.outcome?.name ?: "NONE" }.eachCount().toSortedMap(),
            "finalHops" to requests.groupingBy { it.final?.let { a -> "${a.outcome} ${a.function ?: ""} ${a.hop ?: ""}".trim() } ?: "NONE" }
                .eachCount().toSortedMap(),
        )
    }

    private fun latency(millis: List<Long>): Map<String, Any?> {
        if (millis.isEmpty()) return emptyMap()
        val sorted = millis.sorted()
        fun percentile(p: Double): Long = sorted[(ceil(p / PERCENT * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        return linkedMapOf(
            "count" to sorted.size,
            "p50" to percentile(P50),
            "p95" to percentile(P95),
            "p99" to percentile(P99),
            "max" to sorted.last(),
        )
    }

    fun fault(event: FaultEvent): Map<String, Any?> = linkedMapOf(
        "kind" to event.kind.name,
        "target" to event.target,
        "began" to event.began,
        "injected" to event.injected,
        "recovered" to event.recovered,
        "recoveryMillis" to event.recovered?.let { it - event.began },
        "detail" to event.detail,
    )

    fun request(order: SoakOrder, request: SoakRequest): Map<String, Any?> = linkedMapOf(
        "requestId" to request.requestId,
        "scenario" to order.scenario.name,
        "kind" to request.kind,
        "attempts" to request.attempts.toList().map { attempt ->
            linkedMapOf(
                "at" to attempt.at,
                "millis" to attempt.millis,
                "outcome" to attempt.outcome.name,
                "status" to attempt.status,
                "code" to attempt.code,
                "hop" to attempt.hop,
                "function" to attempt.function,
                "node" to attempt.node,
                "detail" to attempt.detail,
            )
        },
    )

    private const val PERCENT = 100.0
    private const val P50 = 50.0
    private const val P95 = 95.0
    private const val P99 = 99.0
}
