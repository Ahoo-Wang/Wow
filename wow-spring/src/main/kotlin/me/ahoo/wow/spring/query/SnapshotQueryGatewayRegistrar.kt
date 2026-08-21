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
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.gateway.SnapshotQueryGateway
import me.ahoo.wow.query.gateway.SnapshotQueryGatewayFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.core.ResolvableType

class SnapshotQueryGatewayRegistrar : QueryServiceRegistrar() {
    override fun registerQueryService(
        entry: Map.Entry<MaterializedNamedAggregate, Class<*>>,
        registry: BeanDefinitionRegistry
    ) {
        val namedAggregate = entry.key
        val metadata = entry.value.aggregateMetadata<Any, Any>()
        val beanName = "${namedAggregate.toStringWithAlias()}.SnapshotQueryGateway"
        if (registry.containsBeanDefinition(beanName)) {
            log.warn { "SnapshotQueryGateway [$beanName] already exists - Ignore." }
            return
        }
        val gatewayType = ResolvableType.forClassWithGenerics(
            SnapshotQueryGateway::class.java,
            metadata.state.aggregateType
        )
        val beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(gatewayType) {
            appContext.getBean(SnapshotQueryGatewayFactory::class.java).create(metadata)
        }.beanDefinition
        registry.registerBeanDefinition(beanName, beanDefinition)
        log.info { "Register SnapshotQueryGateway [$beanName]." }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
