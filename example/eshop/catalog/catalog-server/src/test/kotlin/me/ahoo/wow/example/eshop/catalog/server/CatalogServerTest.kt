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

package me.ahoo.wow.example.eshop.catalog.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.example.eshop.catalog.api.brand.CreateBrand
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test

/** The catalog service as its `application.yaml` configures it, on random ports. */
class CatalogServerTest {
    private fun start(): WowServer = wowServer<CatalogServer>("dev")

    @Test
    fun `the server should run one node of the catalog context on the memory backend`() {
        start().use { server ->
            val node = server.bean<ServiceNode>()
            node.service.assert().isEqualTo("eshop-catalog-service")
            node.isReady.assert().isTrue()
            node.hostedContexts.assert().containsExactly("eshop-catalog")
            val manifest = server.bean<Manifest>()
            manifest.contexts.single().aggregates.map { it.name }.assert()
                .containsExactlyInAnyOrder("product", "brand", "category")
        }
    }

    /** The application sends a command in process through the host's [CommandClient], as a user module does. */
    @Test
    fun `the application should send a command through the command client`() {
        start().use { server ->
            val commands = server.bean<CommandClient>()
            // The type comes from the command's class; a creation names no id.
            val result = runBlocking { commands.wait(CreateBrand("In-process brand")) }
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.ref.id.assert().isNotBlank()
            result.version.assert().isEqualTo(1)
        }
    }
}
