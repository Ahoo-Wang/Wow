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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys.Dataset
import me.ahoo.wow.openapi.contributor.global.DatasetQueryRouteContributor
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.webflux.ConditionalOnWebfluxEnabled
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.dataset.CompositeDatasetQueryRequestScope
import me.ahoo.wow.webflux.route.dataset.DatasetDirectoryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.dataset.DatasetQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.dataset.DatasetQueryOperation
import me.ahoo.wow.webflux.route.dataset.DatasetQueryRequestScope
import me.ahoo.wow.webflux.route.dataset.DefaultDatasetQueryRequestScope
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import me.ahoo.wow.webflux.route.query.ScopeContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The dataset query routes and the dataset directory, when a dataset query backend serves datasets (such as
 * `wow.query.clickhouse`), whether they are named in `wow.query.datasets` or join at run time: their contracts join
 * the route catalog (and so the router and the OpenAPI document) as a [RouteContributor] bean, and their handlers as
 * [HttpRouteHandlerFunctionFactory] beans, as the BI script route does.
 */
@AutoConfiguration(after = [WebFluxAutoConfiguration::class, QueryAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnBean(DatasetQueryBackendFactory::class)
class DatasetQueryRouteAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.wow.openapi.RouterSpecs"])
    class RouteContractConfiguration {
        @Bean
        fun datasetQueryRouteContributor(): RouteContributor = DatasetQueryRouteContributor
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebfluxEnabled
    @ConditionalOnClass(name = ["me.ahoo.wow.webflux.route.dataset.DatasetQueryHandlerFunction"])
    @ConditionalOnBean(RequestExceptionHandler::class, DatasetQueryGateways::class)
    class WebFluxConfiguration {
        /** Where a dataset route takes the caller's tenant, owner and space; replace it to vouch for them. */
        @Bean
        @ConditionalOnMissingBean
        fun datasetQueryRequestScope(): DatasetQueryRequestScope = DefaultDatasetQueryRequestScope.INSTANCE

        @Bean
        fun datasetDirectoryHandlerFunctionFactory(
            gateways: DatasetQueryGateways,
            exceptionHandler: RequestExceptionHandler,
        ): HttpRouteHandlerFunctionFactory = DatasetDirectoryHandlerFunctionFactory(
            Dataset.DIRECTORY,
            gateways,
            exceptionHandler,
        )

        @Bean
        fun datasetSchemaHandlerFunctionFactory(handlers: Handlers): HttpRouteHandlerFunctionFactory =
            handlers.of(Dataset.SCHEMA, DatasetQueryOperation.SCHEMA)

        @Bean
        fun datasetCountHandlerFunctionFactory(handlers: Handlers): HttpRouteHandlerFunctionFactory =
            handlers.of(Dataset.COUNT, DatasetQueryOperation.COUNT)

        @Bean
        fun datasetAggregationHandlerFunctionFactory(handlers: Handlers): HttpRouteHandlerFunctionFactory =
            handlers.of(Dataset.AGGREGATION, DatasetQueryOperation.AGGREGATION)

        @Bean
        fun datasetListQueryHandlerFunctionFactory(handlers: Handlers): HttpRouteHandlerFunctionFactory =
            handlers.of(Dataset.LIST_QUERY, DatasetQueryOperation.LIST)

        @Bean
        fun datasetPagedQueryHandlerFunctionFactory(handlers: Handlers): HttpRouteHandlerFunctionFactory =
            handlers.of(Dataset.PAGED_QUERY, DatasetQueryOperation.PAGED)

        @Bean
        fun datasetCursorQueryHandlerFunctionFactory(handlers: Handlers): HttpRouteHandlerFunctionFactory =
            handlers.of(Dataset.CURSOR_QUERY, DatasetQueryOperation.CURSOR)

        @Bean
        fun datasetSingleHandlerFunctionFactory(handlers: Handlers): HttpRouteHandlerFunctionFactory =
            handlers.of(Dataset.SINGLE, DatasetQueryOperation.SINGLE)

        @Bean
        fun datasetQueryHandlers(
            gateways: DatasetQueryGateways,
            requestScope: DatasetQueryRequestScope,
            scopeContributors: ObjectProvider<ScopeContributor>,
            exceptionHandler: RequestExceptionHandler,
            guard: HttpQueryGuard,
        ): Handlers = Handlers(
            gateways,
            // Each contributor decides whether it restricts datasets (ScopeContributor.contribute(dataset, …)).
            CompositeDatasetQueryRequestScope.of(requestScope, scopeContributors.orderedStream().toList()),
            exceptionHandler,
            guard,
        )
    }

    /** What every dataset route handler shares. */
    class Handlers(
        private val gateways: DatasetQueryGateways,
        private val requestScope: DatasetQueryRequestScope,
        private val exceptionHandler: RequestExceptionHandler,
        private val guard: HttpQueryGuard,
    ) {
        fun of(handlerKey: String, operation: DatasetQueryOperation): HttpRouteHandlerFunctionFactory =
            DatasetQueryHandlerFunctionFactory(handlerKey, operation, gateways, requestScope, exceptionHandler, guard)
    }
}
