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

import jakarta.annotation.PreDestroy
import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.lifecycle.ForceStoppable
import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.RuntimeLifecycleAdapter
import me.ahoo.wow.runtime.RuntimeOwnershipClaim
import me.ahoo.wow.spring.WowRuntimeComponent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.SmartLifecycle
import org.springframework.core.Ordered
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class RuntimeComponentRegistryTest {

    @Test
    fun `inferred destroy method is disabled`() {
        val beanFactory = dispatcherBeanFactory(AbstractBeanDefinition.INFER_METHOD)

        RuntimeComponentRegistry().postProcessBeanFactory(beanFactory)

        beanFactory.getBeanDefinition(DISPATCHER_BEAN_NAME).destroyMethodName.assert().isEmpty()
    }

    @Test
    fun `explicit destroy method is rejected instead of silently overwritten`() {
        val beanFactory = dispatcherBeanFactory("customDestroy")

        val error = assertThrows<IllegalStateException> {
            RuntimeComponentRegistry().postProcessBeanFactory(beanFactory)
        }

        error.message.assert()
            .contains(DISPATCHER_BEAN_NAME)
            .contains("customDestroy")
    }

    @Test
    fun `explicit method in multiple destroy methods is rejected`() {
        val beanFactory = dispatcherBeanFactory("", "customDestroy")

        val error = assertThrows<IllegalStateException> {
            RuntimeComponentRegistry().postProcessBeanFactory(beanFactory)
        }

        error.message.assert().contains("customDestroy")
    }

    @Test
    fun `disposable bean dispatcher is rejected`() {
        val beanFactory = dispatcherBeanFactory(
            AbstractBeanDefinition.INFER_METHOD,
            dispatcherType = DisposableMessageDispatcher::class.java,
        )

        val error = assertThrows<IllegalStateException> {
            RuntimeComponentRegistry().postProcessBeanFactory(beanFactory)
        }

        error.message.assert().contains("DisposableBean")
    }

    @Test
    fun `Spring lifecycle dispatcher is rejected`() {
        val beanFactory = runtimeOwnedBeanFactory(SpringLifecycleMessageDispatcher::class.java)

        val error = assertThrows<IllegalStateException> {
            RuntimeComponentRegistry().postProcessBeanFactory(beanFactory)
        }

        error.message.assert().contains("Spring Lifecycle")
    }

    @Test
    fun `manually registered Spring lifecycle dispatcher is rejected by snapshot`() {
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerSingleton(
            DISPATCHER_BEAN_NAME,
            SpringLifecycleMessageDispatcher(),
        )
        val registry = RuntimeComponentRegistry()
        registry.postProcessBeanFactory(beanFactory)

        val error = assertThrows<IllegalStateException>(registry::snapshot)

        error.message.assert().contains("Spring Lifecycle")
    }

    @Test
    fun `pre destroy dispatcher is rejected`() {
        val beanFactory = runtimeOwnedBeanFactory(PreDestroyMessageDispatcher::class.java)

        val error = assertThrows<IllegalStateException> {
            RuntimeComponentRegistry().postProcessBeanFactory(beanFactory)
        }

        error.message.assert().contains("@PreDestroy")
    }

    @Test
    fun `inferred destroy method is disabled for explicit runtime component`() {
        val beanFactory = runtimeOwnedBeanFactory(
            RecordingRuntimeComponent::class.java,
            AbstractBeanDefinition.INFER_METHOD,
        )

        RuntimeComponentRegistry().postProcessBeanFactory(beanFactory)

        beanFactory.getBeanDefinition(DISPATCHER_BEAN_NAME).destroyMethodName.assert().isEmpty()
    }

    @Test
    fun `legacy dispatcher descriptor retains identity and exposes an explicit runtime adapter`() {
        val beanFactory = dispatcherBeanFactory()
        val registry = RuntimeComponentRegistry()
        registry.postProcessBeanFactory(beanFactory)
        val dispatcher = beanFactory.getBean(
            DISPATCHER_BEAN_NAME,
            RecordingMessageDispatcher::class.java,
        )

        val descriptor = registry.snapshot().single()

        descriptor.exposedBean.assert().isSameAs(dispatcher)
        descriptor.lifecycleTarget.assert().isSameAs(dispatcher)
        descriptor.runtimeComponent.assert()
            .isInstanceOf(RuntimeLifecycleAdapter::class.java)
    }

    @Test
    fun `native runtime component descriptor uses the target directly`() {
        val beanFactory = runtimeOwnedBeanFactory(RecordingRuntimeComponent::class.java)
        val registry = RuntimeComponentRegistry()
        registry.postProcessBeanFactory(beanFactory)
        val component = beanFactory.getBean(
            DISPATCHER_BEAN_NAME,
            RecordingRuntimeComponent::class.java,
        )

        val descriptor = registry.snapshot().single()

        descriptor.lifecycleTarget.assert().isSameAs(component)
        descriptor.runtimeComponent.assert().isSameAs(component)
    }

    @Test
    fun `destroy force stops a component materialized before snapshot`() {
        val beanFactory = runtimeOwnedBeanFactory(RecordingRuntimeComponent::class.java)
        val registry = RuntimeComponentRegistry()
        registry.postProcessBeanFactory(beanFactory)
        beanFactory.addBeanPostProcessor(registry)
        val component = beanFactory.getBean(
            DISPATCHER_BEAN_NAME,
            RecordingRuntimeComponent::class.java,
        )

        registry.destroy()
        registry.destroy()

        component.forceStopCount.get().assert().isOne()
    }

    @Test
    fun `descriptor resolution failure still force stops the current component`() {
        val beanFactory = runtimeOwnedBeanFactory(FailingOrderRuntimeComponent::class.java)
        val registry = RuntimeComponentRegistry()
        registry.postProcessBeanFactory(beanFactory)
        beanFactory.addBeanPostProcessor(registry)
        val component = beanFactory.getBean(
            DISPATCHER_BEAN_NAME,
            FailingOrderRuntimeComponent::class.java,
        )

        assertThrows<IllegalStateException>(registry::snapshot)
        registry.destroy()

        component.forceStopCount.get().assert().isOne()
    }

    @Test
    fun `recursive snapshot resolution fails without retaining the registry monitor`() {
        val beanFactory = DefaultListableBeanFactory()
        val registry = RuntimeComponentRegistry()
        val beanDefinition = RootBeanDefinition(RecursiveRuntimeComponent::class.java).apply {
            instanceSupplier = Supplier {
                registry.snapshot()
                RecursiveRuntimeComponent()
            }
        }
        beanFactory.registerBeanDefinition(DISPATCHER_BEAN_NAME, beanDefinition)
        registry.postProcessBeanFactory(beanFactory)

        val error = assertThrows<BeanCreationException>(registry::snapshot)

        error.causeMessages().joinToString().assert()
            .contains("membership resolution is recursive")
            .contains("must not depend on WowRuntime")
    }

    private fun dispatcherBeanFactory(
        vararg destroyMethods: String,
        dispatcherType: Class<out MessageDispatcher> = RecordingMessageDispatcher::class.java,
    ): DefaultListableBeanFactory =
        runtimeOwnedBeanFactory(dispatcherType, *destroyMethods)

    private fun runtimeOwnedBeanFactory(
        beanType: Class<*>,
        vararg destroyMethods: String,
    ): DefaultListableBeanFactory {
        val beanFactory = DefaultListableBeanFactory()
        val beanDefinition = RootBeanDefinition(beanType)
        beanDefinition.setDestroyMethodNames(*destroyMethods)
        beanFactory.registerBeanDefinition(DISPATCHER_BEAN_NAME, beanDefinition)
        return beanFactory
    }

    private class RecordingMessageDispatcher :
        MessageDispatcher,
        ForceStoppable {
        override val name: String = "recording"

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() = Unit

        @Suppress("unused")
        fun customDestroy() = Unit
    }

    private class DisposableMessageDispatcher :
        MessageDispatcher,
        ForceStoppable,
        DisposableBean {
        override val name: String = "disposable"

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() = Unit

        override fun destroy() = Unit
    }

    private class SpringLifecycleMessageDispatcher :
        MessageDispatcher,
        ForceStoppable,
        SmartLifecycle {
        override val name: String = "spring-lifecycle"

        @Volatile
        private var running = false

        override fun start() {
            running = true
        }

        override fun stop() {
            stopGracefully().block()
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable {
                running = false
            }

        override fun forceStop() {
            running = false
        }

        override fun isRunning(): Boolean = running
    }

    private class PreDestroyMessageDispatcher :
        MessageDispatcher,
        ForceStoppable {
        override val name: String = "pre-destroy"

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() = Unit

        @PreDestroy
        fun cleanup() = Unit
    }

    private class RecordingRuntimeComponent : WowRuntimeComponent {
        val forceStopCount = AtomicInteger()

        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) = Unit

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() {
            forceStopCount.incrementAndGet()
        }
    }

    private class FailingOrderRuntimeComponent :
        WowRuntimeComponent,
        Ordered {
        val forceStopCount = AtomicInteger()

        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) = Unit

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() {
            forceStopCount.incrementAndGet()
        }

        override fun getOrder(): Int = error("order")
    }

    private class RecursiveRuntimeComponent : WowRuntimeComponent {
        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) = Unit

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() = Unit
    }

    private companion object {
        const val DISPATCHER_BEAN_NAME = "recordingDispatcher"
    }
}

private fun Throwable.causeMessages(): List<String> =
    generateSequence(this) { error -> error.cause }
        .mapNotNull(Throwable::message)
        .toList()
