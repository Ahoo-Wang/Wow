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

import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.ResolvableType
import org.springframework.core.type.AnnotationMetadata

class SnapshotQueryServiceRegistrar : ImportBeanDefinitionRegistrar, BeanFactoryAware {
    companion object {
        private val log = LoggerFactory.getLogger(SnapshotQueryServiceRegistrar::class.java)
    }

    private lateinit var appContext: BeanFactory
    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.appContext = beanFactory
    }

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        MetadataSearcher.namedAggregateType.forEach {
            registerSnapshotQueryService(it, registry)
        }
    }

    private fun registerSnapshotQueryService(
        entry: Map.Entry<MaterializedNamedAggregate, Class<*>>,
        registry: BeanDefinitionRegistry
    ) {
        val namedAggregate = entry.key
        val beanName = "${namedAggregate.toStringWithAlias()}.SnapshotQueryService"
        if (log.isInfoEnabled) {
            log.info("Register SnapshotQueryService [$beanName].")
        }
        if (registry.containsBeanDefinition(beanName)) {
            if (log.isWarnEnabled) {
                log.warn("SnapshotQueryService [$beanName] already exists - Ignore.")
            }
            return
        }
        val genericType = entry.value.aggregateMetadata<Any, Any>().state.aggregateType
        val snapshotQueryServiceType = ResolvableType.forClassWithGenerics(
            SnapshotQueryService::class.java,
            genericType
        )

        val beanDefinitionBuilder = BeanDefinitionBuilder.rootBeanDefinition(snapshotQueryServiceType) {
            val queryServiceFactory: SnapshotQueryServiceFactory =
                appContext.getBeanProvider(SnapshotQueryServiceFactory::class.java).getOrNoOp()
            queryServiceFactory.create<Any>(namedAggregate)
        }

        registry.registerBeanDefinition(beanName, beanDefinitionBuilder.beanDefinition)
    }
}

fun ObjectProvider<SnapshotQueryServiceFactory>.getOrNoOp(): SnapshotQueryServiceFactory {
    return this.getIfAvailable { NoOpSnapshotQueryServiceFactory }
}
