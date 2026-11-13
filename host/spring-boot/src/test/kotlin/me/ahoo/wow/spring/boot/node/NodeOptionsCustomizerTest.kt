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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.spring.boot.HostTestApp
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@OptIn(InternalWowApi::class)
class NodeOptionsCustomizerTest {
    private companion object {
        val TUNED = AppendQueueOptions(maxBatch = 32, mergers = 4, mergeMax = 128)
    }

    @Configuration(proxyBeanMethods = false)
    class Customized {
        @Bean
        fun customizer(): NodeOptionsCustomizer = NodeOptionsCustomizer { options ->
            options.copyWith(appendQueue = TUNED)
        }
    }

    @Test
    fun `without a customizer the node runs with the options the host built`() {
        startHost().use { host ->
            host.node.options.appendQueue.assert().isEqualTo(AppendQueueOptions())
            host.node.options.snapshots.writesInFlight.assert().isNull()
        }
    }

    @Test
    fun `a customizer's result is the options the node runs with, the rest as the host built them`() {
        val plain = startHost().use { it.node.options }
        startHost(sources = listOf(HostTestApp::class.java, Customized::class.java)).use { host ->
            host.node.options.appendQueue.assert().isEqualTo(TUNED)
            host.node.options.copyWith(appendQueue = plain.appendQueue).assert().isEqualTo(plain)
        }
    }
}
