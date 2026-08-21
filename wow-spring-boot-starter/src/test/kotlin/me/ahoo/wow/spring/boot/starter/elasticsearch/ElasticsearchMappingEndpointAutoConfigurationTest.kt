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

package me.ahoo.wow.spring.boot.starter.elasticsearch

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappingRefreshResult
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver
import org.springframework.boot.actuate.endpoint.web.EndpointMapping
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.actuate.endpoint.web.WebFluxEndpointHandlerMapping
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.test.web.reactive.server.HttpHandlerConnector
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono

class ElasticsearchMappingEndpointAutoConfigurationTest {
    private val mappingResolver = mockk<ElasticsearchIndexMappingResolver>()

    @Test
    fun `should safely back off without actuator`() {
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader("org.springframework.boot.actuate"))
            .withConfiguration(AutoConfigurations.of(ElasticsearchMappingEndpointAutoConfiguration::class.java))
            .withBean(ElasticsearchIndexMappingResolver::class.java, { mappingResolver })
            .run { context ->
                context.assert()
                    .hasNotFailed()
                    .doesNotHaveBean(WowElasticsearchMappingEndpoint::class.java)
            }
    }

    @Test
    fun `should safely back off without elasticsearch support`() {
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader("me.ahoo.wow.elasticsearch"))
            .withConfiguration(AutoConfigurations.of(ElasticsearchMappingEndpointAutoConfiguration::class.java))
            .run { context ->
                context.assert()
                    .hasNotFailed()
                    .doesNotHaveBean(WowElasticsearchMappingEndpoint::class.java)
            }
    }

    @Test
    fun `endpoint should be unavailable by default`() {
        webContextRunner()
            .withPropertyValues("management.endpoints.web.exposure.include=wowElasticsearchMapping")
            .run { context ->
                context.assert().hasNotFailed()
                webClient(context.getBean(HttpHandler::class.java))
                    .post()
                    .uri("/actuator/wowElasticsearchMapping/unknown/unknown")
                    .exchange()
                    .expectStatus().isNotFound
            }
    }

    @Test
    fun `enabled endpoint should refresh a registered aggregate`() {
        val namedAggregate = MetadataSearcher.localAggregates.first()
        val indexName = namedAggregate.toSnapshotIndexName()
        val mapping = ElasticsearchIndexMapping.from(indexName, TypeMapping.of { it })
        every { mappingResolver.refresh(indexName) } returns Mono.just(
            ElasticsearchMappingRefreshResult(mapping, changed = true),
        )

        enabledWebContextRunner().run { context ->
            context.assert().hasNotFailed()
                .hasSingleBean(WowElasticsearchMappingEndpoint::class.java)
                .hasSingleBean(WebFluxEndpointHandlerMapping::class.java)
            context.getBean(WebEndpointsSupplier::class.java).endpoints.map { it.rootPath }
                .assert().contains("wowElasticsearchMapping")
            webClient(context.getBean(HttpHandler::class.java))
                .post()
                .uri(
                    "/actuator/wowElasticsearchMapping/{contextName}/{aggregateName}",
                    namedAggregate.contextName,
                    namedAggregate.aggregateName,
                ).accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.scope").isEqualTo("LOCAL_INSTANCE")
                .jsonPath("$.indexName").isEqualTo(indexName)
                .jsonPath("$.fieldCount").isEqualTo(0)
                .jsonPath("$.changed").isEqualTo(true)
                .jsonPath("$.refreshedAt").isNotEmpty
        }

        verify(exactly = 1) { mappingResolver.refresh(indexName) }
    }

    @Test
    fun `unknown aggregate should return bad request`() {
        enabledWebContextRunner().run { context ->
            context.assert().hasNotFailed()
            webClient(context.getBean(HttpHandler::class.java))
                .post()
                .uri("/actuator/wowElasticsearchMapping/unknown/unknown")
                .exchange()
                .expectStatus().isBadRequest
        }

        verify(exactly = 0) { mappingResolver.refresh(any()) }
    }

    private fun enabledWebContextRunner(): ReactiveWebApplicationContextRunner =
        webContextRunner().withPropertyValues(
            "management.endpoint.wowElasticsearchMapping.access=unrestricted",
            "management.endpoints.web.exposure.include=wowElasticsearchMapping",
        )

    private fun webContextRunner(): ReactiveWebApplicationContextRunner =
        ReactiveWebApplicationContextRunner()
            .withInitializer {
                it.environment.conversionService = ApplicationConversionService()
                ConfigurationPropertySources.attach(it.environment)
            }
            .withConfiguration(
                AutoConfigurations.of(
                    EndpointAutoConfiguration::class.java,
                    WebEndpointAutoConfiguration::class.java,
                    WebFluxAutoConfiguration::class.java,
                    HttpHandlerAutoConfiguration::class.java,
                    ElasticsearchMappingEndpointAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(TestEndpointWebFluxConfiguration::class.java)
            .withBean(ElasticsearchIndexMappingResolver::class.java, { mappingResolver })

    private fun webClient(httpHandler: HttpHandler): WebTestClient =
        WebTestClient.bindToServer(HttpHandlerConnector(httpHandler)).build()

    @Configuration(proxyBeanMethods = false)
    class TestEndpointWebFluxConfiguration {
        @Bean
        fun webEndpointReactiveHandlerMapping(
            webEndpointsSupplier: WebEndpointsSupplier,
            endpointMediaTypes: EndpointMediaTypes,
            webEndpointProperties: WebEndpointProperties,
        ): WebFluxEndpointHandlerMapping {
            val endpoints = webEndpointsSupplier.endpoints
            val basePath = webEndpointProperties.basePath
            return WebFluxEndpointHandlerMapping(
                EndpointMapping(basePath),
                endpoints,
                endpointMediaTypes,
                null,
                EndpointLinksResolver(endpoints, basePath),
                false,
            )
        }
    }
}
