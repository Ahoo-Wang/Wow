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
import org.springframework.beans.factory.FactoryBean
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
    fun `custom default lifecycle processor receives the runtime phase timeout`() {
        contextRunner
            .withBean(
                AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                DefaultLifecycleProcessor::class.java,
                { DefaultLifecycleProcessor() },
            )
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context ->
                context.lifecycleProcessor().phaseTimeouts()[WOW_RUNTIME_PHASE]
                    .assert()
                    .isEqualTo(Duration.ofSeconds(3).toMillis())
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
        contextRunner
            .withUserConfiguration(RuntimeComponentFactoryConfiguration::class.java)
            .enableWow()
            .run { context ->
                val component = context.getBean(
                    "factoryRuntimeComponent",
                    RuntimeComponent::class.java,
                )

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
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString()
                    .assert()
                    .contains("springManagedRuntimeComponent")
                    .contains("must not implement Spring Lifecycle")
            }
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

        override fun prepare(runtimeContext: RuntimeContext) {
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
        override fun prepare(runtimeContext: RuntimeContext) = Unit

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

    private class RuntimeComponentFactoryBean : FactoryBean<RuntimeComponent> {
        private val component = PlainRuntimeComponent()

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
