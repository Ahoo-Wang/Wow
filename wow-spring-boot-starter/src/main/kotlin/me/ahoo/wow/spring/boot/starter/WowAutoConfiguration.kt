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

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.naming.CurrentBoundedContext
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.spring.SpringServiceProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Wow AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnWowEnabled
@EnableConfigurationProperties(WowProperties::class)
class WowAutoConfiguration(private val wowProperties: WowProperties) {

    companion object {
        const val SPRING_APPLICATION_NAME = "spring.application.name"
        const val WOW_CURRENT_BOUNDED_CONTEXT = "wow.CurrentBoundedContext"
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
        CurrentBoundedContext.context = currentContext
        return currentContext
    }
}
