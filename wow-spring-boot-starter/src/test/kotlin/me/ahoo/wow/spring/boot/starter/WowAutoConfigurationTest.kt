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
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.exception.ErrorInfoConverterRegistrar
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.spring.WOW_RUNTIME_PHASE
import me.ahoo.wow.spring.WowRuntimeLifecycle
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.SPRING_APPLICATION_NAME
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.Lifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.DefaultLifecycleProcessor
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.annotation.Order
import org.springframework.test.util.ReflectionTestUtils
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class WowAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `loads the canonical runtime boundary`() {
        contextRunner
            .enableWow()
            .run { context ->
                context.assert()
                    .hasSingleBean(WowProperties::class.java)
                    .hasSingleBean(ServiceProvider::class.java)
                    .hasSingleBean(NamedBoundedContext::class.java)
                    .hasSingleBean(ErrorInfoConverterRegistrar::class.java)
                    .hasSingleBean(WowRuntime::class.java)
                    .hasSingleBean(WowRuntimeLifecycle::class.java)
                context.getBean(WowRuntime::class.java).shutdownQuietPeriod
                    .assert()
                    .isEqualTo(Duration.ZERO)
            }
    }

    @Test
    fun `uses the Spring application name when context name is absent`() {
        contextRunner
            .withPropertyValues(
                "wow.enabled=true",
                "$SPRING_APPLICATION_NAME=wow-spring-boot-starter-test",
            )
            .withUserConfiguration(WowAutoConfiguration::class.java)
            .run { context ->
                context.getBean(NamedBoundedContext::class.java).contextName
                    .assert()
                    .isEqualTo("wow-spring-boot-starter-test")
            }
    }

    @Test
    fun `binds runtime shutdown settings`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "wow.shutdown-timeout=2s",
                "wow.shutdown-quiet-period=250ms",
            )
            .run { context ->
                val runtime = context.getBean(WowRuntime::class.java)
                runtime.shutdownTimeout.assert().isEqualTo(Duration.ofSeconds(2))
                runtime.shutdownQuietPeriod.assert().isEqualTo(Duration.ofMillis(250))
            }
    }

    @Test
    fun `runtime phase precedes web ingress and honors its deadline`() {
        WOW_RUNTIME_PHASE.assert().isLessThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE)
        WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE.assert()
            .isLessThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE)

        contextRunner
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context ->
                context.lifecycleProcessor().phaseTimeouts()[WOW_RUNTIME_PHASE]
                    .assert()
                    .isEqualTo(Duration.ofSeconds(3).toMillis())
            }
    }

    @Test
    fun `runtime phase timeout uses the selected runtime deadline`() {
        contextRunner
            .withUserConfiguration(CustomWowRuntimeConfiguration::class.java)
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context ->
                context.lifecycleProcessor().phaseTimeouts()[WOW_RUNTIME_PHASE]
                    .assert()
                    .isEqualTo(Duration.ofMinutes(5).plusSeconds(1).toMillis())
            }
    }

    @Test
    fun `custom default lifecycle processor receives the runtime phase timeout`() {
        contextRunner
            .withBean(
                AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                DefaultLifecycleProcessor::class.java,
                { DefaultLifecycleProcessor() },
            )
            .withUserConfiguration(CustomWowRuntimeConfiguration::class.java)
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context ->
                context.lifecycleProcessor().phaseTimeouts()[WOW_RUNTIME_PHASE]
                    .assert()
                    .isEqualTo(Duration.ofMinutes(5).plusSeconds(1).toMillis())
            }
    }

    @Test
    fun `runtime starts ordered components and stops them in reverse`() {
        val calls = CopyOnWriteArrayList<String>()
        val later = RecordingRuntimeComponent("later", 20, calls)
        val first = RecordingRuntimeComponent("first", 10, calls)

        contextRunner
            .enableWow()
            .withBean("laterComponent", RuntimeComponent::class.java, { later })
            .withBean("firstComponent", RuntimeComponent::class.java, { first })
            .run { context ->
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(first, later)
                calls.assert().containsExactly(
                    "prepare:first",
                    "prepare:later",
                    "start:first",
                    "start:later",
                )
            }

        calls.assert().containsExactly(
            "prepare:first",
            "prepare:later",
            "start:first",
            "start:later",
            "stop:later",
            "stop:first",
        )
    }

    @Test
    fun `runtime honors Spring priority and factory method order metadata`() {
        contextRunner
            .withUserConfiguration(RuntimeComponentOrderingConfiguration::class.java)
            .enableWow()
            .run { context ->
                val priority = context.getBean(
                    "priorityRuntimeComponent",
                    RuntimeComponent::class.java,
                )
                val factoryOrdered = context.getBean(
                    "factoryOrderedRuntimeComponent",
                    RuntimeComponent::class.java,
                )
                val unordered = context.getBean(
                    "unorderedRuntimeComponent",
                    RuntimeComponent::class.java,
                )

                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(priority, factoryOrdered, unordered)
            }
    }

    @Test
    fun `child runtime owns only child context components`() {
        val parentComponent = RecordingRuntimeComponent("parent")
        val childComponent = RecordingRuntimeComponent("child")

        contextRunner
            .enableWow()
            .withBean("parentComponent", RuntimeComponent::class.java, { parentComponent })
            .run { parent ->
                contextRunner
                    .withParent(parent)
                    .enableWow()
                    .withBean("childComponent", RuntimeComponent::class.java, { childComponent })
                    .run { child ->
                        child.getBean(WOW_RUNTIME_BEAN_NAME, WowRuntime::class.java).components
                            .assert()
                            .containsExactly(childComponent)
                        parentComponent.startCount.get().assert().isOne()
                        childComponent.startCount.get().assert().isOne()
                    }

                childComponent.stopCount.get().assert().isOne()
                parentComponent.stopCount.get().assert().isZero()
            }

        parentComponent.stopCount.get().assert().isOne()
    }

    @Test
    fun `runtime discovers a singleton FactoryBean product by its exposed type`() {
        lateinit var component: CloseableRuntimeComponent

        contextRunner
            .withUserConfiguration(RuntimeComponentFactoryConfiguration::class.java)
            .enableWow()
            .run { context ->
                component = context.getBean(
                    "factoryRuntimeComponent",
                    RuntimeComponent::class.java,
                ) as CloseableRuntimeComponent

                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(component)
            }

        component.closeCount.get().assert().isZero()
    }

    @Test
    fun `runtime discovers a manually registered singleton without a bean definition`() {
        val component = PlainRuntimeComponent()

        contextRunner
            .withInitializer { context ->
                context.beanFactory.registerSingleton(
                    "manuallyRegisteredRuntimeComponent",
                    component,
                )
            }
            .enableWow()
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(component)
            }
    }

    @Test
    fun `runtime components must be singleton beans`() {
        contextRunner
            .withUserConfiguration(PrototypeRuntimeComponentConfiguration::class.java)
            .enableWow()
            .run { context ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString()
                    .assert()
                    .contains("prototypeRuntimeComponent")
                    .contains("must be a singleton")
            }
    }

    @Test
    fun `runtime components must not have a competing Spring lifecycle owner`() {
        contextRunner
            .withBean(
                "springManagedRuntimeComponent",
                RuntimeComponent::class.java,
                ::SpringManagedRuntimeComponent,
            )
            .enableWow()
            .run { context ->
                context.assertCompetingOwnerRejected(
                    "springManagedRuntimeComponent",
                    "Spring Lifecycle",
                )
            }
    }

    @Test
    fun `runtime components must not have a competing Spring disposable owner`() {
        contextRunner
            .withBean(
                "disposableRuntimeComponent",
                RuntimeComponent::class.java,
                ::DisposableRuntimeComponent,
            )
            .enableWow()
            .run { context ->
                context.assertCompetingOwnerRejected(
                    "disposableRuntimeComponent",
                    "Spring DisposableBean",
                )
            }
    }

    @Test
    fun `runtime components must not have a competing close owner`() {
        contextRunner
            .withBean(
                "closeableRuntimeComponent",
                RuntimeComponent::class.java,
                ::CloseableRuntimeComponent,
            )
            .enableWow()
            .run { context ->
                context.assertCompetingOwnerRejected(
                    "closeableRuntimeComponent",
                    "AutoCloseable",
                )
            }
    }

    @Test
    fun `runtime components must not have a competing pre destroy owner`() {
        contextRunner
            .withBean(
                "preDestroyRuntimeComponent",
                PreDestroyRuntimeComponent::class.java,
                ::PreDestroyRuntimeComponent,
            )
            .enableWow()
            .run { context ->
                context.assertCompetingOwnerRejected(
                    "preDestroyRuntimeComponent",
                    "destruction callback",
                )
            }
    }

    @Test
    fun `runtime components must not have an explicit destroy method`() {
        contextRunner
            .withUserConfiguration(ExplicitDestroyRuntimeComponentConfiguration::class.java)
            .enableWow()
            .run { context ->
                context.assertCompetingOwnerRejected(
                    "explicitDestroyRuntimeComponent",
                    "destroy method",
                )
            }
    }

    @Test
    fun `runtime components allow explicitly disabled destroy inference`() {
        lateinit var component: CloseableRuntimeComponent

        contextRunner
            .withUserConfiguration(DisabledDestroyRuntimeComponentConfiguration::class.java)
            .enableWow()
            .run { context ->
                context.startupFailure.assert().isNull()
                component = context.getBean(
                    "disabledDestroyRuntimeComponent",
                    CloseableRuntimeComponent::class.java,
                )
            }

        component.closeCount.get().assert().isZero()
    }

    @Test
    fun `runtime rejects a proxied component whose raw target has a destroy owner`() {
        contextRunner
            .withBean(
                "runtimeComponentProxyBeanPostProcessor",
                BeanPostProcessor::class.java,
                ::RuntimeComponentProxyBeanPostProcessor,
            )
            .withUserConfiguration(ProxiedDisposableRuntimeComponentConfiguration::class.java)
            .enableWow()
            .run { context ->
                context.assertCompetingOwnerRejected(
                    "proxiedDisposableRuntimeComponent",
                    "Spring DisposableBean",
                )
            }
    }

    private fun AssertableApplicationContext.assertCompetingOwnerRejected(
        beanName: String,
        owner: String,
    ) {
        startupFailure.assert().isNotNull()
        startupFailure!!.causeMessages().joinToString()
            .assert()
            .contains(beanName)
            .contains(owner)
            .contains("exclusive lifecycle owner")
    }

    private fun AssertableApplicationContext.lifecycleProcessor(): DefaultLifecycleProcessor =
        getBean(
            AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
            DefaultLifecycleProcessor::class.java,
        )

    private class RecordingRuntimeComponent(
        private val componentName: String,
        private val componentOrder: Int = 0,
        private val calls: MutableList<String>? = null,
    ) : RuntimeComponent,
        Ordered {
        val startCount = AtomicInteger()
        val stopCount = AtomicInteger()

        override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
            Mono.fromRunnable {
                calls?.add("prepare:$componentName")
            }

        override fun start() {
            startCount.incrementAndGet()
            calls?.add("start:$componentName")
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable {
                stopCount.incrementAndGet()
                calls?.add("stop:$componentName")
            }

        override fun forceStop() {
            stopCount.incrementAndGet()
            calls?.add("force:$componentName")
        }

        override fun getOrder(): Int = componentOrder
    }

    private open class PlainRuntimeComponent : RuntimeComponent {
        override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() = Unit
    }

    private class PriorityRuntimeComponent :
        PlainRuntimeComponent(),
        PriorityOrdered {
        override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
    }

    @Configuration(proxyBeanMethods = false)
    private class RuntimeComponentOrderingConfiguration {
        @Bean
        fun priorityRuntimeComponent(): RuntimeComponent = PriorityRuntimeComponent()

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        fun factoryOrderedRuntimeComponent(): RuntimeComponent = PlainRuntimeComponent()

        @Bean
        fun unorderedRuntimeComponent(): RuntimeComponent = PlainRuntimeComponent()
    }

    private class SpringManagedRuntimeComponent :
        PlainRuntimeComponent(),
        Lifecycle {
        override fun stop() = Unit

        override fun isRunning(): Boolean = false
    }

    private class DisposableRuntimeComponent :
        PlainRuntimeComponent(),
        DisposableBean {
        override fun destroy() = Unit
    }

    private class CloseableRuntimeComponent :
        PlainRuntimeComponent(),
        AutoCloseable {
        val closeCount = AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class PreDestroyRuntimeComponent : PlainRuntimeComponent() {
        @PreDestroy
        fun destroy() = Unit
    }

    private class ExplicitDestroyRuntimeComponent : PlainRuntimeComponent() {
        fun release() = Unit
    }

    @Configuration(proxyBeanMethods = false)
    private class ExplicitDestroyRuntimeComponentConfiguration {
        @Bean(destroyMethod = "release")
        fun explicitDestroyRuntimeComponent(): RuntimeComponent =
            ExplicitDestroyRuntimeComponent()
    }

    @Configuration(proxyBeanMethods = false)
    private class DisabledDestroyRuntimeComponentConfiguration {
        @Bean(destroyMethod = "")
        fun disabledDestroyRuntimeComponent(): CloseableRuntimeComponent =
            CloseableRuntimeComponent()
    }

    private class RuntimeComponentProxyBeanPostProcessor : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (beanName != "proxiedDisposableRuntimeComponent") {
                return bean
            }
            return ProxyFactory(bean).apply {
                setInterfaces(RuntimeComponent::class.java)
            }.proxy
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class ProxiedDisposableRuntimeComponentConfiguration {
        @Bean
        fun proxiedDisposableRuntimeComponent(): RuntimeComponent =
            DisposableRuntimeComponent()
    }

    private class RuntimeComponentFactoryBean : FactoryBean<RuntimeComponent> {
        private val component = CloseableRuntimeComponent()

        override fun getObject(): RuntimeComponent = component

        override fun getObjectType(): Class<*> = RuntimeComponent::class.java

        override fun isSingleton(): Boolean = true
    }

    @Configuration(proxyBeanMethods = false)
    private class RuntimeComponentFactoryConfiguration {
        @Bean
        fun factoryRuntimeComponent(): RuntimeComponentFactoryBean =
            RuntimeComponentFactoryBean()
    }

    @Configuration(proxyBeanMethods = false)
    private class CustomWowRuntimeConfiguration {
        @Bean(WOW_RUNTIME_BEAN_NAME, destroyMethod = "")
        fun customWowRuntime(): WowRuntime =
            WowRuntime(
                components = emptyList(),
                shutdownTimeout = Duration.ofMinutes(5),
                shutdownQuietPeriod = Duration.ZERO,
            )
    }

    @Configuration(proxyBeanMethods = false)
    private class PrototypeRuntimeComponentConfiguration {
        @Bean
        @Scope("prototype")
        fun prototypeRuntimeComponent(): RuntimeComponent =
            RecordingRuntimeComponent("prototype")
    }
}

private fun Throwable.causeMessages(): List<String> =
    generateSequence(this) { error -> error.cause }
        .mapNotNull(Throwable::message)
        .toList()

@Suppress("UNCHECKED_CAST")
private fun DefaultLifecycleProcessor.phaseTimeouts(): Map<Int, Long> =
    ReflectionTestUtils.getField(this, "timeoutsForShutdownPhases") as Map<Int, Long>
