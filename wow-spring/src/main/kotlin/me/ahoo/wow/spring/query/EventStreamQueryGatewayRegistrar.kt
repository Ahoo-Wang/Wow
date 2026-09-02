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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.requiredQueryModelSchemaProvider
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry

class EventStreamQueryGatewayRegistrar : QueryGatewayRegistrar() {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun registerQueryGateway(
        entry: Map.Entry<MaterializedNamedAggregate, Class<*>>,
        registry: BeanDefinitionRegistry,
    ) {
        val namedAggregate = entry.key
        val beanName = "${namedAggregate.toStringWithAlias()}.EventStreamQueryGateway"
        log.info {
            "Register EventStreamQueryGateway [$beanName]."
        }
        if (registry.containsBeanDefinition(beanName)) {
            log.warn {
                "EventStreamQueryGateway [$beanName] already exists - use it as-is."
            }
            return
        }

        val beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(EventStreamQueryGateway::class.java) {
            val backend = appContext.getBean(EventStreamQueryBackendFactory::class.java).create(namedAggregate)
            val schemaProvider = backend.requiredQueryModelSchemaProvider()
            val validationMode = appContext.getBeanProvider(QuerySchemaValidationMode::class.java)
                .getIfAvailable { QuerySchemaValidationMode.COMPATIBLE }

            @Suppress("UNCHECKED_CAST")
            val filters = appContext.getBeanProvider(QueryFilter::class.java).toList()
                as List<QueryFilter<QueryContext<*, *>>>

            @Suppress("UNCHECKED_CAST")
            val errorHandler = appContext.getBean("eventStreamQueryErrorHandler", ErrorHandler::class.java)
                as ErrorHandler<QueryContext<*, *>>
            DefaultEventStreamQueryGateway(
                namedAggregate = namedAggregate,
                backend = backend,
                schemaProvider = schemaProvider,
                validationMode = validationMode,
                filters = filters,
                errorHandler = errorHandler,
            )
        }.beanDefinition

        registry.registerBeanDefinition(beanName, beanDefinition)
    }
}
