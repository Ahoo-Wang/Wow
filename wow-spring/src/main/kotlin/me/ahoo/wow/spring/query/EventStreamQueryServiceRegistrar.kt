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

package me.ahoo.wow.spring.query

import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry

class EventStreamQueryServiceRegistrar : QueryServiceRegistrar() {
    companion object {
        private val log = LoggerFactory.getLogger(EventStreamQueryServiceRegistrar::class.java)
    }

    override fun registerQueryService(
        entry: Map.Entry<MaterializedNamedAggregate, Class<*>>,
        registry: BeanDefinitionRegistry
    ) {
        val namedAggregate = entry.key
        val beanName = "${namedAggregate.toStringWithAlias()}.EventStreamQueryService"
        if (log.isInfoEnabled) {
            log.info("Register EventStreamQueryService [$beanName].")
        }
        if (registry.containsBeanDefinition(beanName)) {
            if (log.isWarnEnabled) {
                log.warn("EventStreamQueryService [$beanName] already exists - Ignore.")
            }
            return
        }
        val beanDefinitionBuilder = BeanDefinitionBuilder.rootBeanDefinition(EventStreamQueryService::class.java) {
            val queryServiceFactory: EventStreamQueryServiceFactory =
                appContext.getBeanProvider(EventStreamQueryServiceFactory::class.java).getOrNoOp()
            queryServiceFactory.create(namedAggregate)
        }

        registry.registerBeanDefinition(beanName, beanDefinitionBuilder.beanDefinition)
    }
}

fun ObjectProvider<EventStreamQueryServiceFactory>.getOrNoOp(): EventStreamQueryServiceFactory {
    return this.getIfAvailable { NoOpEventStreamQueryServiceFactory }
}
