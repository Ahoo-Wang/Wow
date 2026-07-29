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

import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.exception.ErrorInfoConverterFactory
import me.ahoo.wow.exception.ErrorInfoConverterRegistrar
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.naming.CurrentBoundedContext
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.spring.SpringServiceProvider
import me.ahoo.wow.spring.WowRuntimeLifecycle
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.SearchStrategy
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration
import org.springframework.boot.autoconfigure.context.LifecycleProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.Lifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Role
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.DefaultLifecycleProcessor
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * Wow AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration(before = [LifecycleAutoConfiguration::class])
@ConditionalOnWowEnabled
@EnableConfigurationProperties(WowProperties::class, LifecycleProperties::class)
class WowAutoConfiguration(private val wowProperties: WowProperties) {

    companion object {
        const val SPRING_APPLICATION_NAME = "spring.application.name"
        const val WOW_CURRENT_BOUNDED_CONTEXT = "wow.CurrentBoundedContext"

        @JvmStatic
        @Bean(WOW_RUNTIME_LIFECYCLE_PROCESSOR_CUSTOMIZER_BEAN_NAME)
        @Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
        internal fun wowRuntimeLifecycleProcessorCustomizer(
            environment: Environment,
        ): WowRuntimeLifecycleProcessorCustomizer {
            val shutdownTimeout = Binder.get(environment)
                .bind("${Wow.WOW}.shutdown-timeout", Duration::class.java)
                .orElse(DEFAULT_SHUTDOWN_TIMEOUT)
                ?: DEFAULT_SHUTDOWN_TIMEOUT
            return WowRuntimeLifecycleProcessorCustomizer(shutdownTimeout)
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun serviceProvider(beanFactory: ConfigurableBeanFactory): ServiceProvider {
        return SpringServiceProvider(beanFactory)
    }

    @Primary
    @Bean(WOW_CURRENT_BOUNDED_CONTEXT)
    fun wowCurrentBoundedContext(applicationContext: ApplicationContext): NamedBoundedContext {
        val contextName =
            wowProperties.contextName ?: applicationContext.environment.getRequiredProperty(SPRING_APPLICATION_NAME)
        val currentContext = MaterializedNamedBoundedContext(contextName)
        CurrentBoundedContext.current = currentContext
        return currentContext
    }

    @Bean
    fun errorInfoConverterRegistrar(
        errorInfoConverterFactoryProvider: ObjectProvider<ErrorInfoConverterFactory<*>>
    ): ErrorInfoConverterRegistrar {
        errorInfoConverterFactoryProvider.sortedByOrder().forEach {
            ErrorInfoConverterRegistrar.register(it)
        }
        return ErrorInfoConverterRegistrar
    }

    @Bean(WOW_RUNTIME_BEAN_NAME, destroyMethod = "")
    @ConditionalOnMissingBean(WowRuntime::class, search = SearchStrategy.CURRENT)
    internal fun wowRuntime(
        beanFactory: ConfigurableListableBeanFactory,
    ): WowRuntime {
        return WowRuntime(
            components = beanFactory.localRuntimeComponents(),
            shutdownTimeout = wowProperties.shutdownTimeout,
            shutdownQuietPeriod = wowProperties.shutdownQuietPeriod,
        )
    }

    @Bean(AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
    @ConditionalOnMissingBean(
        name = [AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME],
        search = SearchStrategy.CURRENT,
    )
    fun wowLifecycleProcessor(lifecycleProperties: LifecycleProperties): DefaultLifecycleProcessor {
        return DefaultLifecycleProcessor().apply {
            setTimeoutPerShutdownPhase(lifecycleProperties.timeoutPerShutdownPhase.toMillis())
            configureWowRuntimePhaseTimeout(wowProperties.shutdownTimeout)
        }
    }

    @Bean(WOW_RUNTIME_LIFECYCLE_BEAN_NAME)
    @ConditionalOnMissingBean(WowRuntimeLifecycle::class, search = SearchStrategy.CURRENT)
    internal fun wowRuntimeLifecycle(
        wowRuntime: WowRuntime,
        applicationContext: ConfigurableApplicationContext,
    ): WowRuntimeLifecycle {
        return WowRuntimeLifecycle(wowRuntime) {
            applicationContext.close()
        }
    }

    private fun ConfigurableListableBeanFactory.localRuntimeComponents(): List<RuntimeComponent> =
        getBeanNamesForType(RuntimeComponent::class.java, true, true)
            .map { beanName ->
                require(isSingleton(beanName)) {
                    "RuntimeComponent bean '$beanName' must be a singleton."
                }
                val component = getBean(beanName, RuntimeComponent::class.java)
                require(component !is Lifecycle) {
                    "RuntimeComponent bean '$beanName' must not implement Spring Lifecycle; " +
                        "WowRuntime is its exclusive lifecycle owner."
                }
                beanName to component
            }
            .sortedWith { (leftName, left), (rightName, right) ->
                when {
                    left is PriorityOrdered && right !is PriorityOrdered -> -1
                    right is PriorityOrdered && left !is PriorityOrdered -> 1
                    else -> {
                        val beanFactory = this as? DefaultListableBeanFactory
                        val leftOrder = beanFactory?.getOrder(leftName, left)
                            ?: Ordered.LOWEST_PRECEDENCE
                        val rightOrder = beanFactory?.getOrder(rightName, right)
                            ?: Ordered.LOWEST_PRECEDENCE
                        leftOrder.compareTo(rightOrder)
                    }
                }
            }
            .map { it.second }
}
