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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.spring.query

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.analytics.AnalyticsQueryService
import me.ahoo.wow.query.analytics.AnalyticsQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryErrorCategory
import me.ahoo.wow.query.gateway.QueryExecutionException
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import reactor.core.publisher.Mono

class AnalyticsQueryServiceRegistrar : QueryServiceRegistrar() {
    override fun registerQueryService(
        entry: Map.Entry<MaterializedNamedAggregate, Class<*>>,
        registry: BeanDefinitionRegistry,
    ) {
        val namedAggregate = entry.key
        val beanName = "${namedAggregate.toStringWithAlias()}.AnalyticsQueryService"
        log.info { "Register AnalyticsQueryService [$beanName]." }
        if (registry.containsBeanDefinition(beanName)) {
            log.warn { "AnalyticsQueryService [$beanName] already exists - Ignore." }
            return
        }
        val definition = BeanDefinitionBuilder.rootBeanDefinition(AnalyticsQueryService::class.java) {
            appContext.getBeanProvider(AnalyticsQueryServiceFactory::class.java).getIfAvailable()
                ?.create(namedAggregate)
                ?: UnavailableAnalyticsQueryService(namedAggregate)
        }.beanDefinition
        registry.registerBeanDefinition(beanName, definition)
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

private class UnavailableAnalyticsQueryService(
    override val namedAggregate: MaterializedNamedAggregate,
) : AnalyticsQueryService {
    override fun analyze(
        query: me.ahoo.wow.api.query.analytics.AnalyticsQuery,
    ): Mono<me.ahoo.wow.api.query.analytics.AnalyticsPage> = Mono.error(
        QueryExecutionException(
            QueryErrorCategory.UNSUPPORTED_FEATURE,
            "$.target",
            "SCHEMA_NOT_REGISTERED",
        ),
    )
}
