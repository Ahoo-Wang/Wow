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
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.Filter
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.core.ResolvableType

class SnapshotQueryGatewayRegistrar : QueryGatewayRegistrar() {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun registerQueryGateway(
        entry: Map.Entry<MaterializedNamedAggregate, Class<*>>,
        registry: BeanDefinitionRegistry,
    ) {
        val namedAggregate = entry.key
        val beanName = "${namedAggregate.toStringWithAlias()}.SnapshotQueryGateway"
        log.info {
            "Register SnapshotQueryGateway [$beanName]."
        }
        if (registry.containsBeanDefinition(beanName)) {
            log.warn {
                "SnapshotQueryGateway [$beanName] already exists - use it as-is."
            }
            return
        }

        val stateType = entry.value.aggregateMetadata<Any, Any>().state.aggregateType
        val gatewayType = ResolvableType.forClassWithGenerics(SnapshotQueryGateway::class.java, stateType)
        val beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(gatewayType) {
            val backend = appContext.getBean(SnapshotQueryBackendFactory::class.java).create<Any>(namedAggregate)

            @Suppress("UNCHECKED_CAST")
            val filters = appContext.getBeanProvider(Filter::class.java).toList()
                as List<QueryFilter<QueryContext<*, *>>>

            @Suppress("UNCHECKED_CAST")
            val errorHandler = appContext.getBean("snapshotQueryErrorHandler", ErrorHandler::class.java)
                as ErrorHandler<QueryContext<*, *>>
            DefaultSnapshotQueryGateway<Any>(
                namedAggregate = namedAggregate,
                backend = backend,
                targetType = JsonSerializer.typeFactory.constructParametricType(
                    MaterializedSnapshot::class.java,
                    stateType,
                ),
                filters = filters,
                errorHandler = errorHandler,
            )
        }.beanDefinition

        registry.registerBeanDefinition(beanName, beanDefinition)
    }
}
