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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.toStringWithAlias
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata

class SnapshotQueryServiceRegistrar : ImportBeanDefinitionRegistrar, BeanFactoryAware {
    companion object {
        private val log = LoggerFactory.getLogger(SnapshotQueryServiceRegistrar::class.java)
    }

    protected lateinit var appContext: BeanFactory
    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.appContext = beanFactory
    }

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        MetadataSearcher.localAggregates.forEach {
            registerSnapshotQueryService(it, registry)
        }
    }

    private fun registerSnapshotQueryService(
        namedAggregate: NamedAggregate,
        registry: BeanDefinitionRegistry
    ) {
        val beanName = "${namedAggregate.toStringWithAlias()}.SnapshotQueryService"
        if (log.isInfoEnabled) {
            log.info("Register SnapshotQueryService [$beanName].")
        }
        if (registry.containsBeanDefinition(beanName)) {
            if (log.isWarnEnabled) {
                log.warn(
                    "SnapshotQueryService [$beanName] already exists - Ignore."
                )
            }
            return
        }
        val beanDefinitionBuilder =
            BeanDefinitionBuilder.genericBeanDefinition(SnapshotQueryServiceFactoryBean::class.java)
        beanDefinitionBuilder.addConstructorArgValue(namedAggregate)
        registry.registerBeanDefinition(beanName, beanDefinitionBuilder.beanDefinition)
    }
}
