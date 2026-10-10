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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.ResolvableType
import org.springframework.core.type.AnnotationMetadata

private val log = KotlinLogging.logger {}

/**
 * Registers one query gateway bean per aggregate of the current metadata, unless a bean definition of the same name
 * already exists. Each gateway is created on first use from the collaborators it shares with every other gateway of
 * its [model]: the Catalog schema, the query filters and policies, the entry policy and the model's observer.
 */
sealed class QueryGatewayRegistrar(
    private val model: QueryModel,
    private val observerBeanName: String,
) : ImportBeanDefinitionRegistrar, BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        MetadataSearcher.namedAggregateType.forEach { (namedAggregate, aggregateType) ->
            val beanName = gatewayBeanName(namedAggregate)
            if (registry.containsBeanDefinition(beanName)) {
                log.warn {
                    "Query gateway [$beanName] already exists - use it as-is."
                }
                return@forEach
            }
            log.info {
                "Register query gateway [$beanName]."
            }
            val gateway = gatewayDefinition(namedAggregate, aggregateType)
            val beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(gateway.type) {
                gateway.create(beanFactory, collaborators(namedAggregate))
            }.beanDefinition
            registry.registerBeanDefinition(beanName, beanDefinition)
        }
    }

    internal abstract fun gatewayBeanName(namedAggregate: NamedAggregate): String

    internal abstract fun gatewayDefinition(
        namedAggregate: MaterializedNamedAggregate,
        aggregateType: Class<*>,
    ): QueryGatewayDefinition

    private fun collaborators(namedAggregate: NamedAggregate): QueryGatewayCollaborators =
        QueryGatewayCollaborators(
            schemaProvider = beanFactory.getBean(QuerySchemaCatalog::class.java).provider(namedAggregate, model),
            filters = beanFactory.getBeanProvider(QueryFilter::class.java).toList(),
            policies = beanFactory.getBeanProvider(QueryPolicy::class.java).toList(),
            observer = beanFactory.getBean(observerBeanName, QueryObserver::class.java),
            entryPolicy = beanFactory.getBeanProvider(QueryEntryPolicy::class.java)
                .getIfAvailable { QueryEntryPolicy.DEFAULT },
        )
}

/** The bean type a gateway is registered under, and how it is created from the bean factory and its collaborators. */
internal class QueryGatewayDefinition(
    val type: ResolvableType,
    val create: (BeanFactory, QueryGatewayCollaborators) -> Any,
)

/** What every gateway of one query model shares, resolved from the bean factory when the gateway is created. */
internal class QueryGatewayCollaborators(
    val schemaProvider: QueryModelSchemaProvider,
    val filters: List<QueryFilter>,
    val policies: List<QueryPolicy>,
    val observer: QueryObserver,
    val entryPolicy: QueryEntryPolicy,
)
