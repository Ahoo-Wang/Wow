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
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.exception.DefaultErrorInfoConverter
import me.ahoo.wow.exception.ErrorInfoConverter
import me.ahoo.wow.exception.ErrorInfoConverterFactory
import me.ahoo.wow.exception.ErrorInfoConverterRegistrar
import me.ahoo.wow.infra.lifecycle.ForceStoppable
import me.ahoo.wow.infra.lifecycle.Lifecycle
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.dispatcher.MainDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.RuntimeLifecycleAdapter
import me.ahoo.wow.runtime.RuntimeOwnershipClaim
import me.ahoo.wow.runtime.RuntimePreparable
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.spring.AUTO_REGISTRAR_PHASE
import me.ahoo.wow.spring.MessageDispatcherLauncher
import me.ahoo.wow.spring.WOW_RUNTIME_PHASE
import me.ahoo.wow.spring.WowRuntimeComponent
import me.ahoo.wow.spring.WowRuntimeLifecycle
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.SPRING_APPLICATION_NAME
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.aop.TargetSource
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationContextException
import org.springframework.context.LifecycleProcessor
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.DefaultLifecycleProcessor
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.test.util.ReflectionTestUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Suppress("LargeClass")
internal class WowAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with default configuration`() {
        contextRunner
            .enableWowProperties()
            .withUserConfiguration(WowAutoConfiguration::class.java)
            .withBean(ErrorInfoConverterFactory::class.java, {
                return@withBean object : ErrorInfoConverterFactory<Throwable> {
                    override val supportedType: Class<Throwable>
                        get() = Throwable::class.java

                    override fun create(): ErrorInfoConverter<Throwable> {
                        return DefaultErrorInfoConverter
                    }
                }
            })
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(WowProperties::class.java)
                    .hasSingleBean(ServiceProvider::class.java)
                    .hasSingleBean(NamedBoundedContext::class.java)
                    .hasSingleBean(ErrorInfoConverterRegistrar::class.java)
                    .hasSingleBean(WowRuntime::class.java)
                    .hasSingleBean(WowRuntimeLifecycle::class.java)
                context.getBean(WowRuntime::class.java).shutdownQuietPeriod
                    .assert()
                    .isEqualTo(Duration.ofSeconds(1))
            }
    }

    @Test
    fun `should load context when context name is null`() {
        contextRunner
            .withPropertyValues("$SPRING_APPLICATION_NAME=wow-spring-boot-starter-test")
            .withUserConfiguration(WowAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(WowProperties::class.java)
                    .hasSingleBean(ServiceProvider::class.java)
                    .hasSingleBean(NamedBoundedContext::class.java)
            }
    }

    @Test
    fun `should bind shutdown quiet period`() {
        contextRunner
            .enableWow()
            .withPropertyValues("wow.shutdown-quiet-period=250ms")
            .run { context: AssertableApplicationContext ->
                context.getBean(WowProperties::class.java).shutdownQuietPeriod
                    .assert()
                    .isEqualTo(Duration.ofMillis(250))
            }
    }

    @Test
    fun `registrar runtime and web server phases preserve orchestration order`() {
        AUTO_REGISTRAR_PHASE.assert().isLessThan(WOW_RUNTIME_PHASE)
        WOW_RUNTIME_PHASE.assert().isLessThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE)
        WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE.assert()
            .isLessThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE)
    }

    @Test
    fun `spring lifecycle phase timeout exceeds runtime deadline`() {
        contextRunner
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context: AssertableApplicationContext ->
                val lifecycleProcessor = context.getBean(
                    AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                    DefaultLifecycleProcessor::class.java,
                )

                lifecycleProcessor.phaseTimeouts()[WOW_RUNTIME_PHASE].assert()
                    .isEqualTo(Duration.ofSeconds(3).toMillis())
            }
    }

    @Test
    fun `spring lifecycle phase timeout follows the final runtime deadline`() {
        contextRunner
            .withBean(
                "runtimeTimeoutMutator",
                RuntimeTimeoutMutator::class.java,
                { RuntimeTimeoutMutator(Duration.ofSeconds(5)) },
            )
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context: AssertableApplicationContext ->
                context.getBean(WowRuntime::class.java).shutdownTimeout
                    .assert()
                    .isEqualTo(Duration.ofSeconds(5))
                val lifecycleProcessor = context.getBean(
                    AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                    DefaultLifecycleProcessor::class.java,
                )

                lifecycleProcessor.phaseTimeouts()[WOW_RUNTIME_PHASE].assert()
                    .isEqualTo(Duration.ofSeconds(6).toMillis())
            }
    }

    @Test
    fun `custom default lifecycle processor receives the Wow phase timeout`() {
        contextRunner
            .withBean(
                AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                DefaultLifecycleProcessor::class.java,
                { DefaultLifecycleProcessor() },
            )
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context: AssertableApplicationContext ->
                val lifecycleProcessor = context.getBean(
                    AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                    DefaultLifecycleProcessor::class.java,
                )

                lifecycleProcessor.phaseTimeouts()[WOW_RUNTIME_PHASE].assert()
                    .isEqualTo(Duration.ofSeconds(3).toMillis())
            }
    }

    @Test
    fun `default lifecycle processor declared as its interface is supported`() {
        contextRunner
            .withUserConfiguration(InterfaceLifecycleProcessorConfiguration::class.java)
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                val lifecycleProcessor = context.getBean(
                    AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                    DefaultLifecycleProcessor::class.java,
                )

                lifecycleProcessor.phaseTimeouts()[WOW_RUNTIME_PHASE].assert()
                    .isEqualTo(Duration.ofSeconds(3).toMillis())
            }
    }

    @Test
    fun `duplicate runtime owner fails context refresh`() {
        val extraRuntime = WowRuntime(emptyList(), Duration.ofSeconds(1), Duration.ZERO)

        contextRunner
            .enableWow()
            .withBean("extraWowRuntime", WowRuntime::class.java, { extraRuntime })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("exactly one canonical WowRuntime")
                    .contains("extraWowRuntime")
            }
    }

    @Test
    fun `duplicate runtime lifecycle owner fails context refresh`() {
        val extraRuntime = WowRuntime(emptyList(), Duration.ofSeconds(1), Duration.ZERO)

        contextRunner
            .enableWow()
            .withBean(
                "extraWowRuntimeLifecycle",
                WowRuntimeLifecycle::class.java,
                { WowRuntimeLifecycle(extraRuntime) },
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("exactly one canonical WowRuntimeLifecycle")
                    .contains("extraWowRuntimeLifecycle")
            }
    }

    @Test
    fun `hard context restart fails before higher phase ingress restarts`() {
        val ingress = RecordingIngressLifecycle()

        contextRunner
            .enableWow()
            .withBean(RecordingIngressLifecycle::class.java, { ingress })
            .run { context: AssertableApplicationContext ->
                ingress.startCount.get().assert().isEqualTo(1)
                context.stop()

                val error = assertThrows<ApplicationContextException>(context::start)

                error.causeMessages().joinToString().assert()
                    .contains("Create a new ApplicationContext")
                ingress.startCount.get().assert().isEqualTo(1)
            }
    }

    @Test
    fun `runtime exclusively owns custom dispatcher lifecycle`() {
        val dispatcher = RecordingMessageDispatcher()

        contextRunner
            .enableWow()
            .withBean(RecordingMessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .hasSize(1)
                    .allMatch { component ->
                        component is RuntimeLifecycleAdapter
                    }
                dispatcher.prepareCount.get().assert().isEqualTo(1)
                dispatcher.startCount.get().assert().isEqualTo(1)
            }

        dispatcher.stopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `legacy dispatcher without force stop cannot enter WowRuntime`() {
        contextRunner
            .enableWow()
            .withBean(
                "weakLegacyDispatcher",
                WeakLegacyMessageDispatcher::class.java,
                { WeakLegacyMessageDispatcher() },
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("weakLegacyDispatcher")
                    .contains("ForceStoppable")
                    .contains("RuntimeComponent")
            }
    }

    @Test
    fun `runtime owns the stable CGLIB target instead of the proxy shell`() {
        val target = ProxyableMainDispatcher()
        val interceptedMethods = CopyOnWriteArrayList<String>()
        val proxyFactory = ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(
                MethodInterceptor { invocation ->
                    interceptedMethods += invocation.method.name
                    invocation.proceed()
                },
            )
        }
        val dispatcher = proxyFactory.proxy as MessageDispatcher

        contextRunner
            .enableWow()
            .withBean("proxiedDispatcher", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                AopUtils.isCglibProxy(dispatcher).assert().isTrue()
                AopProxyUtils.ultimateTargetClass(dispatcher)
                    .assert()
                    .isEqualTo(ProxyableMainDispatcher::class.java)
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(target)
                target.prepareCount.get().assert().isEqualTo(1)
                target.startCount.get().assert().isEqualTo(1)
            }

        target.stopCount.get().assert().isEqualTo(1)
        interceptedMethods.assert().doesNotContain(
            "claimRuntimeOwnership",
            "prepare",
            "start",
            "stopGracefully",
            "forceStop",
        )
    }

    @Test
    fun `runtime owns the stable JDK target instead of the proxy shell`() {
        val target = ProxyableMainDispatcher()
        val interceptedMethods = CopyOnWriteArrayList<String>()
        val proxyFactory = ProxyFactory().apply {
            setTarget(target)
            setInterfaces(
                MessageDispatcher::class.java,
                RuntimeComponent::class.java,
            )
            addAdvice(
                MethodInterceptor { invocation ->
                    interceptedMethods += invocation.method.name
                    invocation.proceed()
                },
            )
        }
        val dispatcher = proxyFactory.proxy as MessageDispatcher

        contextRunner
            .enableWow()
            .withBean("proxiedDispatcher", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                AopUtils.isJdkDynamicProxy(dispatcher).assert().isTrue()
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(target)
                target.prepareCount.get().assert().isEqualTo(1)
                target.startCount.get().assert().isEqualTo(1)
            }

        target.stopCount.get().assert().isEqualTo(1)
        interceptedMethods.assert().doesNotContain(
            "claimRuntimeOwnership",
            "prepare",
            "start",
            "stopGracefully",
            "forceStop",
        )
    }

    @Test
    fun `JDK proxy may hide managed contract when its static target exposes it`() {
        val target = ProxyableMainDispatcher()
        val proxyFactory = ProxyFactory().apply {
            setTarget(target)
            setInterfaces(
                MessageDispatcher::class.java,
                RuntimePreparable::class.java,
                ForceStoppable::class.java,
            )
        }
        val dispatcher = proxyFactory.proxy as MessageDispatcher

        contextRunner
            .enableWow()
            .withBean("narrowedProxyDispatcher", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(target)
                target.prepareCount.get().assert().isEqualTo(1)
                target.startCount.get().assert().isEqualTo(1)
            }
        target.stopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `runtime component proxy must use a static target source`() {
        val target = ProxyableMainDispatcher()
        val targetSource = object : TargetSource {
            override fun getTargetClass(): Class<*> = ProxyableMainDispatcher::class.java

            override fun isStatic(): Boolean = false

            override fun getTarget(): Any = target

            override fun releaseTarget(target: Any) = Unit
        }
        val dispatcher = ProxyFactory().apply {
            setTargetSource(targetSource)
            setInterfaces(
                MessageDispatcher::class.java,
                RuntimeComponent::class.java,
            )
        }.proxy as MessageDispatcher

        contextRunner
            .enableWow()
            .withBean("dynamicTargetDispatcher", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("dynamicTargetDispatcher")
                    .contains("non-static TargetSource")
                    .contains("stable singleton")
            }
    }

    @Test
    fun `opaque runtime component proxy is rejected`() {
        val target = ProxyableMainDispatcher()
        val dispatcher = ProxyFactory(target).apply {
            isProxyTargetClass = true
            isOpaque = true
        }.proxy as MessageDispatcher

        contextRunner
            .enableWow()
            .withBean("opaqueDispatcher", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("opaqueDispatcher")
                    .contains("opaque AOP proxy")
                    .contains("TargetSource")
            }
    }

    @Test
    fun `proxy and singleton target cannot both be runtime components`() {
        val target = ProxyableMainDispatcher()
        val dispatcher = ProxyFactory(target).apply {
            isProxyTargetClass = true
        }.proxy as MessageDispatcher

        contextRunner
            .enableWow()
            .withBean("dispatcherTarget", MessageDispatcher::class.java, { target })
            .withBean("dispatcherProxy", MessageDispatcher::class.java, { dispatcher })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("dispatcherTarget")
                    .contains("dispatcherProxy")
                    .contains("same singleton lifecycle target")
            }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy dispatcher launcher cannot coexist with canonical runtime owner`() {
        val dispatcher = RecordingMessageDispatcher("legacy-owned")
        val launcher = MessageDispatcherLauncher(dispatcher, Duration.ofSeconds(1))

        contextRunner
            .enableWow()
            .withBean("legacyOwnedDispatcher", MessageDispatcher::class.java, { dispatcher })
            .withBean(
                "legacyDispatcherLauncher",
                MessageDispatcherLauncher::class.java,
                { launcher },
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("legacyDispatcherLauncher")
                    .contains("second lifecycle owner")
                    .contains("WowRuntimeLifecycle")
            }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `lazy broadly declared legacy launcher cannot bypass ownership validation`() {
        val dispatcher = RecordingMessageDispatcher("lazy-legacy-owned")

        contextRunner
            .enableWow()
            .withBean("lazyLegacyOwnedDispatcher", MessageDispatcher::class.java, { dispatcher })
            .withBean(
                "hiddenLegacyLauncher",
                SmartLifecycle::class.java,
                {
                    MessageDispatcherLauncher(
                        dispatcher,
                        Duration.ofSeconds(1),
                    )
                },
                { beanDefinition ->
                    beanDefinition.isLazyInit = true
                },
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("hiddenLegacyLauncher")
                    .contains("second lifecycle owner")
                    .contains("WowRuntimeLifecycle")
            }
    }

    @Test
    fun `failed runtime construction force stops materialized components`() {
        val component = RecordingRuntimeComponent("construction-fallback")

        contextRunner
            .enableWow()
            .withPropertyValues(
                "wow.shutdown-timeout=1s",
                "wow.shutdown-quiet-period=1s",
            )
            .withBean(RecordingRuntimeComponent::class.java, { component })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("shutdownQuietPeriod")
                    .contains("shorter than shutdownTimeout")
            }

        component.startCount.get().assert().isZero()
        component.stopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `failed runtime construction force stops components in reverse runtime order`() {
        val calls = CopyOnWriteArrayList<String>()
        val later = RecordingRuntimeComponent("later", order = 20, calls = calls)
        val first = RecordingRuntimeComponent("first", order = 10, calls = calls)

        contextRunner
            .enableWow()
            .withPropertyValues(
                "wow.shutdown-timeout=1s",
                "wow.shutdown-quiet-period=1s",
            )
            .withBean("laterRuntimeComponent", RecordingRuntimeComponent::class.java, { later })
            .withBean("firstRuntimeComponent", RecordingRuntimeComponent::class.java, { first })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("shutdownQuietPeriod")
            }

        calls.assert().containsExactly(
            "force-stop:later",
            "force-stop:first",
        )
    }

    @Test
    fun `runtime includes only explicitly marked lifecycle extensions`() {
        val registered = RecordingRuntimeComponent()
        val unregistered = UnregisteredWowLifecycle()

        contextRunner
            .enableWow()
            .withBean(RecordingRuntimeComponent::class.java, { registered })
            .withBean(UnregisteredWowLifecycle::class.java, { unregistered })
            .run { context: AssertableApplicationContext ->
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(registered)
                registered.startCount.get().assert().isEqualTo(1)
                unregistered.startCount.get().assert().isZero()
            }

        registered.stopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `non singleton runtime component fails context refresh`() {
        contextRunner
            .enableWow()
            .withBean(
                "prototypeRuntimeComponent",
                RecordingRuntimeComponent::class.java,
                { RecordingRuntimeComponent() },
                { beanDefinition ->
                    beanDefinition.scope = ConfigurableBeanFactory.SCOPE_PROTOTYPE
                },
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("prototypeRuntimeComponent")
                    .contains("must be singleton")
            }
    }

    @Test
    fun `scoped proxy runtime component fails context refresh`() {
        contextRunner
            .withUserConfiguration(ScopedRuntimeComponentConfiguration::class.java)
            .enableWow()
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains("scopedRuntimeComponent")
                    .contains("scoped proxy")
                    .contains("must be singleton")
            }
    }

    @Test
    fun `properly declared lazy runtime component is materialized into the runtime snapshot`() {
        contextRunner
            .withUserConfiguration(LazyRuntimeComponentConfiguration::class.java)
            .enableWow()
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .hasSize(1)
                    .allMatch { component ->
                        component is LazyRecordingRuntimeComponent
                    }
            }
    }

    @Test
    fun `broad non-lazy runtime component declaration fails context refresh`() {
        contextRunner
            .withUserConfiguration(BroadRuntimeComponentConfiguration::class.java)
            .enableWow()
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeMessages().joinToString().assert()
                    .contains(BROAD_RUNTIME_COMPONENT_BEAN_NAME)
                    .contains("declared Spring bean type")
                    .contains("WowRuntimeComponent")
            }
    }

    @Test
    fun `broad lazy runtime component fails on materialization without losing destroy metadata`() {
        contextRunner
            .withUserConfiguration(BroadLazyRuntimeComponentConfiguration::class.java)
            .enableWow()
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                val beanDefinition =
                    context.beanFactory.getBeanDefinition(BROAD_LAZY_RUNTIME_COMPONENT_BEAN_NAME)
                beanDefinition.destroyMethodName.assert()
                    .isEqualTo(AbstractBeanDefinition.INFER_METHOD)

                val error = assertThrows<BeanCreationException> {
                    context.getBean(BROAD_LAZY_RUNTIME_COMPONENT_BEAN_NAME)
                }

                error.causeMessages().joinToString().assert()
                    .contains(BROAD_LAZY_RUNTIME_COMPONENT_BEAN_NAME)
                    .contains("declared Spring bean type")
                    .contains("WowRuntimeComponent")
                beanDefinition.destroyMethodName.assert()
                    .isEqualTo(AbstractBeanDefinition.INFER_METHOD)
            }
    }

    @Test
    fun `FactoryBean product is runtime owned while Spring destroys only the factory`() {
        val factory = RuntimeComponentFactoryBean()

        contextRunner
            .enableWow()
            .withBean(
                FACTORY_RUNTIME_COMPONENT_BEAN_NAME,
                RuntimeComponentFactoryBean::class.java,
                { factory },
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .containsExactly(factory.product)
                factory.product.startCount.get().assert().isEqualTo(1)
                factory.destroyCount.get().assert().isZero()
                factory.product.closeCount.get().assert().isZero()
            }

        factory.product.stopCount.get().assert().isEqualTo(1)
        factory.product.closeCount.get().assert().isZero()
        factory.destroyCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `unrelated scoped target style bean name is not treated as a scoped proxy`() {
        val dispatcher = RecordingMessageDispatcher("foo")

        contextRunner
            .enableWow()
            .withBean("foo", RecordingMessageDispatcher::class.java, { dispatcher })
            .withBean("scopedTarget.foo", String::class.java, { "unrelated" })
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .hasSize(1)
                    .allMatch { component ->
                        component is RuntimeLifecycleAdapter
                    }
            }

        dispatcher.stopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `child runtime owns only child context components`() {
        val parentDispatcher = RecordingMessageDispatcher("parent-dispatcher", order = 100)
        val parentComponent = RecordingRuntimeComponent("parent-component", order = 200)
        val childDispatcher = RecordingMessageDispatcher("child-dispatcher", order = 10)
        val childComponent = RecordingRuntimeComponent("child-component", order = 20)

        contextRunner
            .enableWow()
            .withBean("parentDispatcher", RecordingMessageDispatcher::class.java, { parentDispatcher })
            .withBean("parentRuntimeComponent", RecordingRuntimeComponent::class.java, { parentComponent })
            .run { parent: AssertableApplicationContext ->
                contextRunner
                    .withParent(parent)
                    .enableWow()
                    .withBean("childDispatcher", RecordingMessageDispatcher::class.java, { childDispatcher })
                    .withBean("childRuntimeComponent", RecordingRuntimeComponent::class.java, { childComponent })
                    .run { child: AssertableApplicationContext ->
                        val childComponents = child.getBean(WowRuntime::class.java).components
                        childComponents.assert().hasSize(2)
                        childComponents[0].assert()
                            .isInstanceOf(RuntimeLifecycleAdapter::class.java)
                        childComponents[1].assert().isSameAs(childComponent)
                        parentDispatcher.startCount.get().assert().isEqualTo(1)
                        parentComponent.startCount.get().assert().isEqualTo(1)
                    }
            }
    }

    @Test
    fun `early custom lifecycle processor still receives Wow phase timeout`() {
        contextRunner
            .withUserConfiguration(EarlyLifecycleProcessorConfiguration::class.java)
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context: AssertableApplicationContext ->
                val lifecycleProcessor = context.getBean(
                    AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                    DefaultLifecycleProcessor::class.java,
                )

                lifecycleProcessor.phaseTimeouts()[WOW_RUNTIME_PHASE].assert()
                    .isEqualTo(Duration.ofSeconds(3).toMillis())
            }
    }

    @Test
    fun `priority ordered bean factory post processor cannot bypass Wow phase timeout`() {
        contextRunner
            .withUserConfiguration(PriorityOrderedLifecycleProcessorConfiguration::class.java)
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context: AssertableApplicationContext ->
                val lifecycleProcessor = context.getBean(
                    AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                    DefaultLifecycleProcessor::class.java,
                )

                lifecycleProcessor.phaseTimeouts()[WOW_RUNTIME_PHASE].assert()
                    .isEqualTo(Duration.ofSeconds(3).toMillis())
            }
    }

    @Test
    fun `custom lifecycle processor phase timeouts are preserved`() {
        val customPhase = WOW_RUNTIME_PHASE - 1
        val customPhaseTimeout = Duration.ofSeconds(7).toMillis()

        contextRunner
            .withBean(
                AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                DefaultLifecycleProcessor::class.java,
                { DefaultLifecycleProcessor() },
                { beanDefinition ->
                    beanDefinition.propertyValues.add(
                        "timeoutsForShutdownPhases",
                        mapOf(customPhase to customPhaseTimeout),
                    )
                },
            )
            .enableWow()
            .withPropertyValues("wow.shutdown-timeout=2s")
            .run { context: AssertableApplicationContext ->
                val phaseTimeouts = context.getBean(
                    AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                    DefaultLifecycleProcessor::class.java,
                ).phaseTimeouts()

                phaseTimeouts.assert()
                    .containsEntry(customPhase, customPhaseTimeout)
                    .containsEntry(WOW_RUNTIME_PHASE, Duration.ofSeconds(3).toMillis())
            }
    }

    @Test
    fun `runtime honors component order and stops in reverse`() {
        val calls = CopyOnWriteArrayList<String>()
        val later = RecordingMessageDispatcher("later", order = 20, calls = calls)
        val first = RecordingMessageDispatcher("first", order = 10, calls = calls)

        contextRunner
            .enableWow()
            .withBean("laterDispatcher", RecordingMessageDispatcher::class.java, { later })
            .withBean("firstDispatcher", RecordingMessageDispatcher::class.java, { first })
            .run { context: AssertableApplicationContext ->
                context.getBean(WowRuntime::class.java).components
                    .assert()
                    .hasSize(2)
                    .allMatch { component ->
                        component is RuntimeLifecycleAdapter
                    }
                calls.assert().containsExactly("start:first", "start:later")
            }

        calls.assert().containsExactly(
            "start:first",
            "start:later",
            "stop:later",
            "stop:first",
        )
    }

    @Test
    fun `runtime globally orders dispatchers and explicit components`() {
        val calls = CopyOnWriteArrayList<String>()
        val dispatcher = RecordingMessageDispatcher("dispatcher", order = 20, calls = calls)
        val component = RecordingRuntimeComponent("component", order = 10, calls = calls)

        contextRunner
            .enableWow()
            .withBean("orderedDispatcher", RecordingMessageDispatcher::class.java, { dispatcher })
            .withBean("orderedRuntimeComponent", RecordingRuntimeComponent::class.java, { component })
            .run { context: AssertableApplicationContext ->
                val runtimeComponents = context.getBean(WowRuntime::class.java).components
                runtimeComponents.assert().hasSize(2)
                runtimeComponents[0].assert().isSameAs(component)
                runtimeComponents[1].assert()
                    .isInstanceOf(RuntimeLifecycleAdapter::class.java)
                calls.assert().containsExactly("start:component", "start:dispatcher")
            }

        calls.assert().containsExactly(
            "start:component",
            "start:dispatcher",
            "stop:dispatcher",
            "stop:component",
        )
    }

    @Test
    fun `late bean factory mutation cannot restore inferred runtime component destruction`() {
        val dispatcher = RecordingMessageDispatcher("late-mutated")

        contextRunner
            .enableWow()
            .withBean(LATE_MUTATED_DISPATCHER, RecordingMessageDispatcher::class.java, { dispatcher })
            .withBean(
                LateRuntimeOwnedDestroyMethodMutator::class.java,
                { LateRuntimeOwnedDestroyMethodMutator() },
            )
            .run { context: AssertableApplicationContext ->
                val startupFailure = context.startupFailure
                if (startupFailure != null) {
                    startupFailure.causeMessages().joinToString().assert()
                        .contains("Runtime-owned")
                        .contains("destroy")
                    return@run
                }
                context.beanFactory.getBeanDefinition(LATE_MUTATED_DISPATCHER)
                    .destroyMethodName
                    .assert()
                    .isEmpty()
            }
    }

    private class RecordingMessageDispatcher(
        override val name: String = "recording",
        private val order: Int = 0,
        private val calls: MutableList<String>? = null,
    ) : MessageDispatcher,
        ForceStoppable,
        RuntimePreparable,
        Ordered {
        val prepareCount = AtomicInteger()
        val startCount = AtomicInteger()
        val stopCount = AtomicInteger()

        override fun prepare(runtimeContext: RuntimeContext) {
            prepareCount.incrementAndGet()
        }

        override fun start() {
            startCount.incrementAndGet()
            calls?.add("start:$name")
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable {
                stopCount.incrementAndGet()
                calls?.add("stop:$name")
            }

        override fun forceStop() {
            stopCount.incrementAndGet()
            calls?.add("force-stop:$name")
        }

        override fun getOrder(): Int = order
    }

    private class WeakLegacyMessageDispatcher : MessageDispatcher {
        override val name: String = "weak-legacy"

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()
    }

    private open class ProxyableMainDispatcher : MainDispatcher<String>() {
        val prepareCount = AtomicInteger()
        val startCount = AtomicInteger()
        val stopCount = AtomicInteger()

        override val name: String = "proxyable-main"
        override val namedAggregates: Set<NamedAggregate> = emptySet()

        override fun receiveMessage(subscription: MessageSubscription): Flux<String> =
            Flux.empty()

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>,
        ): MessageDispatcher = error("No aggregate dispatcher is expected.")

        override fun prepareManaged(runtimeContext: RuntimeContext) {
            prepareCount.incrementAndGet()
        }

        override fun startManaged() {
            startCount.incrementAndGet()
        }

        override fun stopManagedGracefully(): Mono<Void> {
            stopCount.incrementAndGet()
            return Mono.empty()
        }
    }

    private class RecordingIngressLifecycle : SmartLifecycle {
        val startCount = AtomicInteger()

        @Volatile
        private var running = false

        override fun start() {
            startCount.incrementAndGet()
            running = true
        }

        override fun stop() {
            running = false
        }

        override fun isRunning(): Boolean = running

        override fun getPhase(): Int = WOW_RUNTIME_PHASE + 1
    }

    private class RecordingRuntimeComponent(
        private val componentName: String = "runtime-component",
        private val order: Int = 0,
        private val calls: MutableList<String>? = null,
    ) : WowRuntimeComponent, Ordered {
        val startCount = AtomicInteger()
        val stopCount = AtomicInteger()

        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) = Unit

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
            calls?.add("force-stop:$componentName")
        }

        override fun getOrder(): Int = order
    }

    private class UnregisteredWowLifecycle : Lifecycle {
        val startCount = AtomicInteger()

        override fun start() {
            startCount.incrementAndGet()
        }

        override fun stopGracefully(): Mono<Void> = Mono.empty()
    }

    private class RuntimeTimeoutMutator(
        private val shutdownTimeout: Duration,
    ) : org.springframework.beans.factory.config.BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is WowProperties) {
                bean.shutdownTimeout = shutdownTimeout
            }
            return bean
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class InterfaceLifecycleProcessorConfiguration {
        @Bean(AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
        fun lifecycleProcessor(): LifecycleProcessor = DefaultLifecycleProcessor()
    }

    @Configuration(proxyBeanMethods = false)
    private class EarlyLifecycleProcessorConfiguration {
        @Bean(AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
        fun lifecycleProcessor(): DefaultLifecycleProcessor = DefaultLifecycleProcessor()

        @Bean
        fun earlyLifecycleProcessorBeanPostProcessor(
            lifecycleProcessor: DefaultLifecycleProcessor,
        ): EarlyLifecycleProcessorBeanPostProcessor =
            EarlyLifecycleProcessorBeanPostProcessor(lifecycleProcessor)
    }

    private class EarlyLifecycleProcessorBeanPostProcessor(
        @Suppress("unused")
        private val lifecycleProcessor: DefaultLifecycleProcessor,
    ) : org.springframework.beans.factory.config.BeanPostProcessor, PriorityOrdered {
        override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
    }

    @Configuration(proxyBeanMethods = false)
    private class PriorityOrderedLifecycleProcessorConfiguration {
        @Bean(AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
        fun lifecycleProcessor(): DefaultLifecycleProcessor = DefaultLifecycleProcessor()

        @Bean
        fun priorityOrderedLifecycleProcessorBeanFactoryPostProcessor(
            lifecycleProcessor: DefaultLifecycleProcessor,
        ): PriorityOrderedLifecycleProcessorBeanFactoryPostProcessor =
            PriorityOrderedLifecycleProcessorBeanFactoryPostProcessor(lifecycleProcessor)
    }

    private class PriorityOrderedLifecycleProcessorBeanFactoryPostProcessor(
        @Suppress("unused")
        private val lifecycleProcessor: DefaultLifecycleProcessor,
    ) : BeanFactoryPostProcessor, PriorityOrdered {
        override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit

        override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
    }

    private class LateRuntimeOwnedDestroyMethodMutator : BeanFactoryPostProcessor, Ordered {
        override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
            beanFactory.getBeanDefinition(LATE_MUTATED_DISPATCHER).destroyMethodName =
                AbstractBeanDefinition.INFER_METHOD
        }

        override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
    }

    @Configuration(proxyBeanMethods = false)
    private class ScopedRuntimeComponentConfiguration {
        @Bean
        @Scope(
            value = ConfigurableBeanFactory.SCOPE_PROTOTYPE,
            proxyMode = ScopedProxyMode.TARGET_CLASS,
        )
        fun scopedRuntimeComponent(): ScopedRecordingRuntimeComponent =
            ScopedRecordingRuntimeComponent()
    }

    @Configuration(proxyBeanMethods = false)
    private class LazyRuntimeComponentConfiguration {
        @Bean
        @Lazy
        fun lazyRuntimeComponent(): WowRuntimeComponent =
            LazyRecordingRuntimeComponent()
    }

    @Configuration(proxyBeanMethods = false)
    private class BroadRuntimeComponentConfiguration {
        @Bean(BROAD_RUNTIME_COMPONENT_BEAN_NAME)
        fun broadRuntimeComponent(): RuntimeComponent =
            LazyRecordingRuntimeComponent()
    }

    @Configuration(proxyBeanMethods = false)
    private class BroadLazyRuntimeComponentConfiguration {
        @Bean(BROAD_LAZY_RUNTIME_COMPONENT_BEAN_NAME)
        @Lazy
        fun broadLazyRuntimeComponent(): RuntimeComponent =
            LazyRecordingRuntimeComponent()
    }

    private class RuntimeComponentFactoryBean :
        FactoryBean<FactoryProductRuntimeComponent>,
        DisposableBean {
        val product = FactoryProductRuntimeComponent()
        val destroyCount = AtomicInteger()

        override fun getObject(): FactoryProductRuntimeComponent = product

        override fun getObjectType(): Class<*> = FactoryProductRuntimeComponent::class.java

        override fun isSingleton(): Boolean = true

        override fun destroy() {
            destroyCount.incrementAndGet()
        }
    }

    private companion object {
        const val BROAD_LAZY_RUNTIME_COMPONENT_BEAN_NAME = "broadLazyRuntimeComponent"
        const val BROAD_RUNTIME_COMPONENT_BEAN_NAME = "broadRuntimeComponent"
        const val FACTORY_RUNTIME_COMPONENT_BEAN_NAME = "factoryRuntimeComponent"
        const val LATE_MUTATED_DISPATCHER = "lateMutatedDispatcher"
    }
}

private class LazyRecordingRuntimeComponent : WowRuntimeComponent {
    override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
        RuntimeOwnershipClaim.shared(this)

    override fun prepare(runtimeContext: RuntimeContext) = Unit

    override fun start() = Unit

    override fun stopGracefully(): Mono<Void> = Mono.empty()

    override fun forceStop() = Unit
}

private class FactoryProductRuntimeComponent :
    WowRuntimeComponent,
    AutoCloseable {
    val startCount = AtomicInteger()
    val stopCount = AtomicInteger()
    val closeCount = AtomicInteger()

    override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
        RuntimeOwnershipClaim.shared(this)

    override fun prepare(runtimeContext: RuntimeContext) = Unit

    override fun start() {
        startCount.incrementAndGet()
    }

    override fun stopGracefully(): Mono<Void> =
        Mono.fromRunnable {
            stopCount.incrementAndGet()
        }

    override fun forceStop() {
        stopCount.incrementAndGet()
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}

private open class ScopedRecordingRuntimeComponent : WowRuntimeComponent {
    override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
        RuntimeOwnershipClaim.shared(this)

    override fun prepare(runtimeContext: RuntimeContext) = Unit

    override fun start() = Unit

    override fun stopGracefully(): Mono<Void> = Mono.empty()

    override fun forceStop() = Unit
}

private fun Throwable.causeMessages(): List<String> =
    generateSequence(this) { error -> error.cause }
        .mapNotNull(Throwable::message)
        .toList()

@Suppress("UNCHECKED_CAST")
private fun DefaultLifecycleProcessor.phaseTimeouts(): Map<Int, Long> =
    ReflectionTestUtils.getField(this, "timeoutsForShutdownPhases") as Map<Int, Long>
