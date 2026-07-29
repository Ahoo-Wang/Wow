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
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.DefaultLifecycleProcessor

/**
 * Wow AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration(after = [LifecycleAutoConfiguration::class])
@ConditionalOnWowEnabled
@EnableConfigurationProperties(WowProperties::class)
class WowAutoConfiguration(private val wowProperties: WowProperties) {

    companion object {
        const val SPRING_APPLICATION_NAME = "spring.application.name"
        const val WOW_CURRENT_BOUNDED_CONTEXT = "wow.CurrentBoundedContext"
        const val WOW_RUNTIME_BEAN_NAME = "wowRuntime"
        const val WOW_RUNTIME_LIFECYCLE_BEAN_NAME = "wowRuntimeLifecycle"
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
    @ConditionalOnMissingBean
    fun wowRuntime(runtimeComponents: ObjectProvider<RuntimeComponent>): WowRuntime {
        return WowRuntime(
            components = runtimeComponents.orderedStream().toList(),
            shutdownTimeout = wowProperties.shutdownTimeout,
            shutdownQuietPeriod = wowProperties.shutdownQuietPeriod,
        )
    }

    @Bean(WOW_RUNTIME_LIFECYCLE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun wowRuntimeLifecycle(
        wowRuntime: WowRuntime,
        applicationContext: ConfigurableApplicationContext,
    ): WowRuntimeLifecycle {
        return WowRuntimeLifecycle(wowRuntime) {
            applicationContext.close()
        }
    }

    @Bean
    fun wowRuntimeLifecycleProcessorConfigurer(
        @Qualifier(AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
        lifecycleProcessorProvider: ObjectProvider<DefaultLifecycleProcessor>,
    ): SmartInitializingSingleton =
        WowRuntimeLifecycleProcessorConfigurer(
            lifecycleProcessorProvider = lifecycleProcessorProvider,
            shutdownTimeout = wowProperties.shutdownTimeout,
        )
}
