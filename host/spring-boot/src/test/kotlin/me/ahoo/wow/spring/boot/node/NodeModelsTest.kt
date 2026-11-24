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

package me.ahoo.wow.spring.boot.node

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import java.lang.reflect.Proxy

class NodeModelsTest {
    @Test
    fun `a bean factory with no class loader should read the contracts and builds on the default one`() {
        // The interface allows a factory with no class loader, though Spring's own always has one.
        val factory = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ConfigurableListableBeanFactory::class.java),
        ) { _, method, _ ->
            check(method.name == "getBeanClassLoader") { "Not this test's: ${method.name}" }
            null
        } as ConfigurableListableBeanFactory
        val manifest = Manifest(service = ServiceInfo("shop"))
        val identity = NodeIdentity("shop", "n-1", null, "127.0.0.1", "127.0.0.1", "spring.application.name")
        NodeModels().wowContractDirectory(manifest, identity, factory).local.assert().isSameAs(manifest)
    }
}
