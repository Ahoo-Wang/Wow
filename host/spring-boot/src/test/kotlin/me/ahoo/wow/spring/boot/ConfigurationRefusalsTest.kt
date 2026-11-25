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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Every setting below refuses the start, as `operate/configuration.md` says a value the host cannot start with does: a
 * `WowConfigurationException` naming the key, or the group of keys above it (#7032). The key reaches the message in one
 * of two ways: an options object built from the properties is built inside `configured("<group>")`, which turns the
 * runtime's `IllegalArgumentException` into one of the group; a value no options object checks is checked where it
 * is read, with `configCheck` and its key. Before, these refused with the runtime's bare sentence (`The sample interval
 * is positive.`, `Invalid duration: PT0S`, `inProcessTimeout must be positive.`), and `wow.wait.default-timeout=0s`
 * with `wow.cluster`'s, whose forward timeout follows the default wait.
 */
class ConfigurationRefusalsTest {
    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("refused")
    fun `a setting the host cannot start with should be refused naming its key`(key: String, value: String) {
        val extra = EXTRA[key].orEmpty()
        val failure = assertThrows<Exception> { startHost(key to value, *extra.toTypedArray()).close() }
        val named = generateSequence<Throwable>(failure) { it.cause?.takeIf { cause -> cause !== it } }
            .filterIsInstance<WowConfigurationException>()
            .firstOrNull()
        named.assert().describedAs("$key=$value was refused with no key: ${rootOf(failure)}").isNotNull()
        key.startsWith(named!!.key).assert().describedAs("$key refused naming ${named.key}").isTrue()
    }

    /** A control: a setting of a group the host already names, refused naming that group. */
    @Test
    fun `a node option should be refused naming its group, as the host already does`() {
        val failure = assertThrows<Exception> { startHost("wow.executor.max-batch" to "0").close() }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
            .key.assert().isEqualTo("wow.executor")
    }

    private fun rootOf(failure: Throwable): String =
        generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }.last().let {
            "${it.javaClass.simpleName}: ${it.message}"
        }

    companion object {
        /** Settings a case needs beside its own: the webhook's timeout is read only with its URL. */
        private val EXTRA: Map<String, List<Pair<String, Any>>> = mapOf(
            "wow.ops.webhook.timeout" to listOf("wow.ops.webhook.url" to "http://127.0.0.1:1/hook"),
        )

        @JvmStatic
        fun refused(): List<Arguments> = listOf(
            // The node's sampling and its internal port.
            "wow.node.sample-interval" to "0s",
            "wow.node.max-parked" to "0",
            "wow.internal.connect-timeout" to "0s",
            "wow.internal.signal-queue-capacity" to "0",
            "wow.internal.port" to "70000",
            // The node's health (HealthOptions, built by wowNodeHealth outside the named checks).
            "wow.health.stores.down-after" to "-1s",
            "wow.health.stores.window" to "0s",
            "wow.health.ownership.handover-grace" to "-1s",
            "wow.health.executor.saturation" to "1.5",
            "wow.health.subscriptions.lag-degraded-at" to "-1",
            "wow.health.subscriptions.parked-degraded-at" to "0",
            // The ledger's publishing and the webhook.
            "wow.ops.publish.interval" to "0s",
            "wow.ops.publish.context" to " ",
            "wow.ops.webhook.timeout" to "0s",
            // The calls to peers.
            "wow.peer.connect-timeout" to "0s",
            "wow.peer.timeout" to "0s",
            "wow.peer.retry.max-attempts" to "0",
            "wow.peer.retry.initial-backoff" to "-1s",
            "wow.peer.retry.max-retry-after" to "-1s",
            // The logs' tails.
            "wow.log.poll-interval" to "0s",
            "wow.log.max-poll-interval" to "10ms",
            // The callers' tenant.
            "wow.tenant.default" to " ",
            // The default wait: refused today as wow.cluster's forward timeout, which the operator did not set.
            "wow.wait.default-timeout" to "0s",
            // The HTTP edge.
            "wow.http.errors.[wow.command.busy].status" to "200",
            // The query plane.
            "wow.query.max-wait" to "-1s",
            "wow.query.max-queued" to "-1",
            "wow.query.unbounded-timeout" to "-1s",
            "wow.query.adaptive.floor" to "0",
            "wow.query.budgets.http.timeout" to "-1s",
            "wow.query.budgets.in-process.count-limit" to "-1",
            "wow.query.count-cache.ttl" to "-1s",
            "wow.query.count-cache.max-entries" to "-1",
            "wow.query.state.max-replay" to "0",
            "wow.query.state.min-version-wait" to "-1s",
            "wow.query.stream.chunk-size" to "0",
            "wow.query.stream.max-hold" to "-1s",
            "wow.query.dataset.max-wait" to "-1s",
        ).map { (key, value) -> Arguments.of(key, value) }
    }
}
