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

package me.ahoo.wow.spring.boot.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.exception.RecoverableExceptionProvider
import me.ahoo.wow.exception.RecoverableExceptionRegistry
import me.ahoo.wow.exception.recoverable
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.MessagePropagator
import me.ahoo.wow.messaging.propagation.MessagePropagators
import me.ahoo.wow.tck.wire.WireSamples
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * F8: header propagation and the recoverable-exception classification are beans. The default bean writes exactly the
 * keys of the ServiceLoader propagators (the G1 wire contract); application beans add to it; the request-header
 * propagator is switched by configuration.
 */
internal class PropagationAndRecoverableBeansTest {
    private val contextRunner = ApplicationContextRunner().enableWow()

    private fun MessagePropagators.headerOf(upstream: Message<*, *>): Header =
        DefaultHeader.empty().also { propagate(it, upstream) }

    @Test
    fun `the default propagators bean writes the same keys and values as the ServiceLoader propagators`() {
        contextRunner.run { context ->
            val command = WireSamples.commandMessage()
            val bean = context.getBean(MessagePropagators::class.java)

            bean.headerOf(command).toMap().assert().isEqualTo(MessagePropagators.DEFAULT.headerOf(command).toMap())
            bean.headerOf(command).keys.assert().contains("user_agent", "remote_ip", "command_wait_id")
        }
    }

    @Test
    fun `a MessagePropagator bean joins the propagators`() {
        contextRunner
            .withBean("tenantHint", MessagePropagator::class.java, { TenantHintPropagator })
            .run { context ->
                val header = context.getBean(MessagePropagators::class.java).headerOf(WireSamples.commandMessage())

                header["tenant_hint"].assert().isEqualTo("from-bean")
            }
    }

    @Test
    fun `request header propagation is switched off by configuration`() {
        contextRunner
            .withPropertyValues("wow.messaging.propagation.request=false")
            .run { context ->
                val header = context.getBean(MessagePropagators::class.java).headerOf(WireSamples.commandMessage())

                header.keys.assert().doesNotContain("user_agent", "remote_ip")
                header["command_wait_id"].assert().isNotNull()
            }
    }

    @Test
    fun `a RecoverableExceptionProvider bean classifies through the registry bean`() {
        contextRunner
            .withBean("beanProvider", RecoverableExceptionProvider::class.java, { BeanRecoverableProvider })
            .run { context ->
                val registry = context.getBean(RecoverableExceptionRegistry::class.java)
                try {
                    registry.assert().isSameAs(RecoverableExceptionRegistry.DEFAULT)
                    BeanClassifiedException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
                } finally {
                    registry.unregister(BeanClassifiedException::class.java)
                }
            }
    }
}

private object TenantHintPropagator : MessagePropagator {
    override fun propagate(header: Header, upstream: Message<*, *>) {
        header.with("tenant_hint", "from-bean")
    }
}

private class BeanClassifiedException : RuntimeException()

private object BeanRecoverableProvider : RecoverableExceptionProvider {
    override fun register(registrar: me.ahoo.wow.exception.RecoverableExceptionRegistrar) {
        registrar.register(BeanClassifiedException::class.java, RecoverableType.RECOVERABLE)
    }
}
