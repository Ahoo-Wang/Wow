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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.dispatcher.MainDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.RuntimeOwnershipClaim
import me.ahoo.wow.spring.WowRuntimeComponent
import org.junit.jupiter.api.Test
import org.springframework.aop.TargetSource
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class WowRuntimeProxyOwnershipTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `custom static target source and direct target cannot both be runtime components`() {
        val target = ProxyableDispatcher()
        val targetSource = object : TargetSource {
            override fun getTargetClass(): Class<*> = ProxyableDispatcher::class.java

            override fun isStatic(): Boolean = true

            override fun getTarget(): Any = target
        }
        val dispatcher = newManagedDispatcherProxy(targetSource)

        contextRunner
            .enableWow()
            .withBean("customStaticTarget", MessageDispatcher::class.java, { target })
            .withBean("customStaticProxy", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("customStaticTarget")
                    .contains("customStaticProxy")
                    .contains("same singleton lifecycle target")
            }
    }

    @Test
    fun `nested proxies and direct target cannot both be runtime components`() {
        val target = ProxyableDispatcher()
        val innerProxy = newManagedDispatcherProxy(target)
        val outerProxy = newManagedDispatcherProxy(innerProxy)

        contextRunner
            .enableWow()
            .withBean("nestedProxyTarget", MessageDispatcher::class.java, { target })
            .withBean("nestedProxy", MessageDispatcher::class.java, { outerProxy })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("nestedProxyTarget")
                    .contains("nestedProxy")
                    .contains("same singleton lifecycle target")
            }
    }

    @Test
    fun `cyclic static target source is rejected`() {
        lateinit var dispatcher: MessageDispatcher
        val targetSource = object : TargetSource {
            override fun getTargetClass(): Class<*> = MessageDispatcher::class.java

            override fun isStatic(): Boolean = true

            override fun getTarget(): Any = dispatcher
        }
        dispatcher = newManagedDispatcherProxy(targetSource)

        contextRunner
            .enableWow()
            .withBean("cyclicTargetDispatcher", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("cyclicTargetDispatcher")
                    .contains("cyclic AOP target chain")
            }
    }

    @Test
    fun `unresolvable static target source is rejected`() {
        val targetSource = object : TargetSource {
            override fun getTargetClass(): Class<*> = MessageDispatcher::class.java

            override fun isStatic(): Boolean = true

            override fun getTarget(): Any? = null
        }
        val dispatcher = newManagedDispatcherProxy(targetSource)

        contextRunner
            .enableWow()
            .withBean("unresolvableTargetDispatcher", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("unresolvableTargetDispatcher")
                    .contains("resolved to null")
                    .contains("stable lifecycle target")
            }
    }

    @Test
    fun `narrow JDK proxy cannot hide a runtime component target from membership validation`() {
        val target = ProxyableRuntimeComponent()
        val component = ProxyFactory().apply {
            setTarget(target)
            setInterfaces(RuntimeComponent::class.java)
        }.proxy as RuntimeComponent

        contextRunner
            .enableWow()
            .withBean("hiddenRuntimeComponent", RuntimeComponent::class.java, { component })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("hiddenRuntimeComponent")
                    .contains("declared Spring bean type")
                    .contains("WowRuntimeComponent")
            }
    }

    private fun newManagedDispatcherProxy(target: Any): MessageDispatcher =
        ProxyFactory().apply {
            setTarget(target)
            setInterfaces(
                MessageDispatcher::class.java,
                RuntimeComponent::class.java,
            )
        }.proxy as MessageDispatcher

    private fun newManagedDispatcherProxy(targetSource: TargetSource): MessageDispatcher =
        ProxyFactory().apply {
            setTargetSource(targetSource)
            setInterfaces(
                MessageDispatcher::class.java,
                RuntimeComponent::class.java,
            )
        }.proxy as MessageDispatcher

    private open class ProxyableDispatcher : MainDispatcher<String>() {
        override val name: String = "proxyable"
        override val namedAggregates: Set<NamedAggregate> = emptySet()

        override fun receiveMessage(subscription: MessageSubscription): Flux<String> = Flux.empty()

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>,
        ): MessageDispatcher = error("No aggregate dispatcher is expected.")
    }

    private class ProxyableRuntimeComponent : WowRuntimeComponent {
        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) = Unit

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() = Unit
    }
}

private fun Throwable.causeMessages(): List<String> =
    generateSequence(this) { error -> error.cause }
        .mapNotNull(Throwable::message)
        .toList()
