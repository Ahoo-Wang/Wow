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

package me.ahoo.wow.spring.boot.starter.modeling

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.ResolvableType
import org.springframework.core.annotation.AnnotationUtils

/**
 * Fails startup when the context holds a command filter, which 9.3.0 no longer calls (V5): an
 * `ExchangeFilter<ServerCommandExchange<*>>` bean, or an `ExchangeFilter` bean with `@FilterType(CommandDispatcher::class)`.
 * Without this check such a bean would be silently ignored. The error names the bean and the replacement.
 *
 * It runs once every singleton exists and before the runtime starts, so no command is processed without the filter.
 */
internal class RetiredCommandFilterCheck(
    private val beanFactory: ConfigurableListableBeanFactory,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val retired = beanFactory.getBeanNamesForType(ExchangeFilter::class.java, true, false)
            .filter { isCommandFilter(it) }
        check(retired.isEmpty()) {
            "Command filters are no longer called since Wow 9.3.0, so these beans would be ignored: $retired. " +
                "Replace each by the extension point for what it did: a CommandInstrumentation bean (tracing, " +
                "metrics, logging), a CommandValidator or Jakarta validation on the command (checks), or an event " +
                "processor, saga or projection (reacting to committed events). See the 9.3.0 migration guide, " +
                "\"Command Filters Replaced by a Fixed Pipeline\"."
        }
    }

    private fun isCommandFilter(beanName: String): Boolean {
        val beanType = beanFactory.getType(beanName, false) ?: return false
        val filterType = AnnotationUtils.findAnnotation(beanType, FilterType::class.java)
        if (filterType != null && filterType.value.any { CommandDispatcher::class.java.isAssignableFrom(it.java) }) {
            return true
        }
        return exchangeTypes(beanName, beanType).any { ServerCommandExchange::class.java.isAssignableFrom(it) }
    }

    /** The exchange type the filter declares, from the bean definition (a `@Bean` method's return type) and its class. */
    private fun exchangeTypes(beanName: String, beanType: Class<*>): List<Class<*>> {
        val declared = if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.getMergedBeanDefinition(beanName).resolvableType
        } else {
            ResolvableType.NONE
        }
        return listOf(declared, ResolvableType.forClass(beanType)).mapNotNull {
            it.`as`(ExchangeFilter::class.java).getGeneric(0).resolve()
        }
    }
}
