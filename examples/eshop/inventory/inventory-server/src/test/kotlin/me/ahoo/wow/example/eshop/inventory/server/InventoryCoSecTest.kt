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

package me.ahoo.wow.example.eshop.inventory.server

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spring.boot.security.DevelopmentCallerResolver
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Who calls the inventory service: the host's development callers in development, CoSec's behind the gateway (the
 * `cosec` profile, decisions D296, D301); outside development, a node without CoSec does not start rather than take
 * its callers from headers any client sets.
 */
class InventoryCoSecTest {
    @Test
    @OptIn(ExperimentalWowApi::class) // wow-cosec is experimental in 10.0
    fun `behind the gateway the caller should be CoSec's`() {
        wowServer<InventoryServer>("cosec", properties = ONLY_GATEWAY_CAN_REACH + DEPLOYED_IN_MEMORY).use { server ->
            server.bean<CallerResolver>().assert().isInstanceOf(CoSecCallerResolver::class.java)
        }
    }

    @Test
    fun `in development the callers should be the host's`() {
        wowServer<InventoryServer>("dev").use { server ->
            server.bean<CallerResolver>().assert().isInstanceOf(DevelopmentCallerResolver::class.java)
        }
    }

    @Test
    fun `a node outside development without CoSec should not start`() {
        val failure = assertThrows<Exception> {
            wowServer<InventoryServer>(properties = mapOf("wow.internal.host" to "0.0.0.0")).close()
        }
        generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            .assert().contains("No durable backend", "wow.dev-profiles")
    }
}

/**
 * CoSec's statement that only its gateway reaches the node ([IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY]), which the
 * gateway mode of the `cosec` profile asks of every host: a test is reached by its own client alone. A deployment
 * states it with the wow-service chart's NetworkPolicy.
 */
@OptIn(ExperimentalCoSecApi::class) // CoSec's test kit is experimental in 8.0.
private val ONLY_GATEWAY_CAN_REACH: Map<String, Any> = mapOf(IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY to "true")

/**
 * A node behind the gateway is a deployment, development being a profile of `wow.dev-profiles` alone (#6972): its
 * backend in memory, named on purpose, as a deployment of one node may.
 */
private val DEPLOYED_IN_MEMORY: Map<String, Any> = mapOf("wow.backend" to "memory")
