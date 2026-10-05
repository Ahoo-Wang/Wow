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

package me.ahoo.wow.spring.boot.starter.openapi

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contributor.DefaultRouteContributors
import me.ahoo.wow.openapi.contributor.aggregate.event.EventRouteContributor
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.WOW_CURRENT_BOUNDED_CONTEXT
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnClass(name = ["me.ahoo.wow.openapi.RouterSpecs"])
@EnableConfigurationProperties(OpenAPIProperties::class)
class OpenAPIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OpenAPIComponentContext::class)
    fun openAPIComponentContext(
        @Qualifier(WOW_CURRENT_BOUNDED_CONTEXT) currentContext: NamedBoundedContext
    ): OpenAPIComponentContext {
        val openAPIComponentContext = OpenAPIComponentContext
            .default(
                inline = false,
                defaultSchemaNamePrefix = currentContext.getContextAliasPrefix()
            )
        return openAPIComponentContext
    }

    /**
     * The route catalog: the default routes plus every [RouteContributor] bean (the BI script route, for one, comes
     * from the BI auto-configuration when `wow-bi` is on the classpath). The event compensate route needs an
     * [EventCompensateSupporter] (registered by the compensation auto-configuration); without one its contract is left
     * out, so neither the router nor the OpenAPI document offers a route that has no handler.
     */
    @Bean
    fun routerSpecs(
        @Qualifier(WOW_CURRENT_BOUNDED_CONTEXT) boundedContext: NamedBoundedContext,
        openAPIComponentContext: OpenAPIComponentContext,
        routeContributors: ObjectProvider<RouteContributor>,
        eventCompensateSupporter: ObjectProvider<EventCompensateSupporter>,
    ): RouterSpecs {
        val eventCompensation = eventCompensateSupporter.ifAvailable != null
        val contributors = (DefaultRouteContributors.all() + routeContributors.orderedStream().toList())
            .map { contributor ->
                if (contributor === EventRouteContributor && !eventCompensation) {
                    RouteContractFilter(contributor) { it.handlerKey != BuiltInHttpRouteHandlerKeys.Event.COMPENSATE }
                } else {
                    contributor
                }
            }
        return RouterSpecs(
            boundedContext,
            componentContext = openAPIComponentContext,
            routeContributors = contributors,
        ).build()
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnOpenAPIEnabled
    @ConditionalOnClass(name = ["org.springdoc.core.customizers.OpenApiCustomizer"])
    class SpringdocConfiguration {
        @Bean
        fun wowOpenApiCustomizer(routerSpecs: RouterSpecs): WowOpenApiCustomizer {
            return WowOpenApiCustomizer(routerSpecs)
        }
    }
}
