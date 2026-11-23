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

package me.ahoo.wow.runtime.node

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.wait.WaitOptions
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The `wow-service` chart's grace period (`deploy/helm/wow-service/templates/_helpers.tpl`) is a copy of
 * [ServiceNodeOptions.stopBound] in Helm's template language, with the preStop pause, the public server's longest wait
 * and a margin around it. The chart's unit tests (`tests/deployment_test.yaml`) hold what it renders, case by case; this
 * test holds the same cases to [ServiceNodeOptions.stopBound], so a change of the formula on either side fails one of
 * the two. The chart's files are read as their own text: the `shutdown:` block of `values.yaml`, and in each case of the
 * unit tests its `shutdown.*` settings and the grace period it asserts.
 */
class ChartGracePeriodTest {
    private val chart = File("../../deploy/helm/wow-service").canonicalFile

    /** A case of the chart's unit tests that asserts the grace period: its shutdown values and the figure it renders. */
    private data class Case(val name: String, val shutdown: Map<String, Long>, val gracePeriodSeconds: Long)

    /** The chart's default shutdown values: the keys of the `shutdown:` block of `values.yaml`, in seconds. */
    private fun defaults(): Map<String, Long> =
        File(chart, "values.yaml").readLines().dropWhile { it != "shutdown:" }.drop(1)
            .takeWhile { it.isBlank() || it.startsWith(" ") }
            .mapNotNull { DEFAULT.matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2].toLong() }

    private fun cases(): List<Case> {
        val defaults = defaults()
        val cases = mutableListOf<Case>()
        var name: String? = null
        var set = mutableMapOf<String, Long>()
        var expecting = false
        File(chart, "tests/deployment_test.yaml").readLines().forEach { line ->
            CASE.matchEntire(line)?.let {
                name = it.groupValues[1]
                set = mutableMapOf()
            }
            SET.matchEntire(line)?.let { set[it.groupValues[1]] = it.groupValues[2].toLong() }
            if (line.trim() == "path: $GRACE_PERIOD") {
                expecting = true
            } else if (expecting) {
                expecting = false
                VALUE.matchEntire(line)?.let { cases += Case(name!!, defaults + set, it.groupValues[1].toLong()) }
            }
        }
        return cases
    }

    /** What the chart renders for [shutdown], as the node bounds its stop. */
    private fun gracePeriodSeconds(shutdown: Map<String, Long>): Long {
        val stopTimeout = shutdown.getValue("subscriptionStopTimeoutSeconds").seconds
        val options = ServiceNodeOptions(
            cluster = ClusterOptions(drainTimeout = shutdown.getValue("drainTimeoutSeconds").seconds),
            wait = WaitOptions(maxTimeout = shutdown.getValue("waitMaxTimeoutSeconds").seconds),
            subscription = SubscriptionOptions(stopTimeout = stopTimeout),
        )
        return shutdown.getValue("preStopSeconds") + shutdown.getValue("waitMaxTimeoutSeconds") +
            options.stopBound.inWholeSeconds + shutdown.getValue("marginSeconds")
    }

    @Test
    fun `every grace period the chart's unit tests assert should be the node's stop bound with the pauses around it`() {
        val cases = cases()
        // The defaults, and each side of the stop bound's max: the waits, and the subscriptions' stops.
        cases.map { it.gracePeriodSeconds }.assert().contains(170, 290, 410)
        val wrong = cases.mapNotNull { case ->
            val node = gracePeriodSeconds(case.shutdown)
            "${case.name}: the chart renders ${case.gracePeriodSeconds}, the node bounds $node"
                .takeIf { node != case.gracePeriodSeconds }
        }
        wrong.assert().describedAs(wrong.joinToString("\n")).isEmpty()
    }

    private companion object {
        const val GRACE_PERIOD = "spec.template.spec.terminationGracePeriodSeconds"
        val DEFAULT = Regex("""\s+(\w+Seconds):\s*(\d+)\s*""")
        val CASE = Regex("""\s+- it:\s*(.+)""")
        val SET = Regex("""\s+shutdown\.(\w+):\s*(\d+)\s*""")
        val VALUE = Regex("""\s+value:\s*(\d+)\s*""")
    }
}
