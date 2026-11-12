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

package me.ahoo.wow.benchmark.subscriptions

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.benchmark.transfer.TransferServer
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * The server as bench.sh starts it, in memory: the projections the property names are the node's subscriptions, as an
 * operator reads them (`/wow/ops/subscriptions`, for a caller that reaches every tenant).
 */
class SubscriptionsServerTest {
    private fun subscriptionsOf(projections: Int?): Set<String> = wowServer<TransferServer>(
        properties = mapOf(
            "wow.backend" to "memory",
            "wow.transport" to "memory",
            "wow.http.ops.enabled" to true,
            BenchProjections.PROPERTY to projections,
        ),
        sources = listOf(ProjectionsConfiguration::class.java, OperatorCaller::class.java),
    ).use { server ->
        val answer = server.http.get("/wow/ops/subscriptions")
        answer.status.assert().describedAs(answer.text).isEqualTo(OK)
        answer.body!!["subscriptions"].values().map { it["subscription"].asString() }.toSet()
    }

    @Test
    fun `two projections are two subscriptions beside the saga's`() {
        val subscriptions = subscriptionsOf(2)
        subscriptions.assert().contains("transfer.NoOpProjection1.onEvent", "transfer.NoOpProjection2.onEvent")
        subscriptions.assert().doesNotContain("transfer.NoOpProjection3.onEvent")
        subscriptions.filter { it.startsWith("transfer.TransferSaga.") }.assert().isNotEmpty()
    }

    @Test
    fun `without the property the node has none`() {
        subscriptionsOf(null).filter { "NoOpProjection" in it }.assert().isEmpty()
    }

    private companion object {
        const val OK = 200
    }
}

/** An operator who reaches every tenant, over the benchmark's anonymous callers, so the node's subscriptions read. */
@Configuration(proxyBeanMethods = false)
class OperatorCaller {
    @Bean
    @Primary
    fun operatorCallerResolver(): CallerResolver {
        val operator = Caller.of(subject = "bench-operator", crossScope = true)
        return CallerResolver { operator }
    }
}
