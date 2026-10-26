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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * Who calls the inventory service: the anonymous caller in development, CoSec's behind the gateway (the `cosec`
 * profile, decisions D296, D301); outside development, a node without CoSec does not start rather than serve every
 * request anonymously.
 */
class InventoryCoSecTest {
    @Test
    fun `behind the gateway the caller should be CoSec's`() {
        SpringApplicationBuilder(InventoryServer::class.java).profiles("cosec").run(*NODE).use { context ->
            context.getBean(CallerResolver::class.java).assert().isInstanceOf(CoSecCallerResolver::class.java)
        }
    }

    @Test
    fun `in development the caller should be anonymous`() {
        SpringApplicationBuilder(InventoryServer::class.java).run(*NODE).use { context ->
            context.getBean(CallerResolver::class.java).assert().isSameAs(CallerResolver.ANONYMOUS)
        }
    }

    @Test
    fun `a node outside development without CoSec should not start`() {
        val failure = assertThrows<Exception> {
            SpringApplicationBuilder(InventoryServer::class.java)
                .run(
                    *(NODE.filterNot { it.startsWith("--wow.internal.host=") } + "--wow.internal.host=0.0.0.0").toTypedArray()
                )
                .close()
        }
        generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            .assert().contains(InventoryServer.COSEC_ENABLED)
    }

    private companion object {
        val NODE = arrayOf(
            "--spring.main.banner-mode=off",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--wow.internal.port=0",
            "--wow.internal.host=127.0.0.1",
        )
    }
}
