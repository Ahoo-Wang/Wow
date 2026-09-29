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

package me.ahoo.wow.viewstore.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.viewstore.starter.system.SystemViews
import me.ahoo.wow.webflux.route.command.extractor.CommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode

/**
 * The starter embedded in a host of another context (the example's), on in-memory stores.
 */
@SpringBootTest(
    classes = [ViewStoreStarterTest.HostApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.application.name=example-service",
        "wow.command.bus.type=in_memory",
        "wow.event.bus.type=in_memory",
        "wow.eventsourcing.state.bus.type=in_memory",
        "wow.kafka.enabled=false",
        "wow.mongo.enabled=false",
        "wow.eventsourcing.store.storage=in_memory",
        "wow.eventsourcing.snapshot.storage=in_memory",
        "cosid.machine.enabled=true",
        "cosid.machine.distributor.type=manual",
        "cosid.machine.distributor.manual.machine-id=1",
        "cosid.generator.enabled=true",
    ],
)
class ViewStoreStarterTest {
    @SpringBootApplication
    class HostApplication {
        @Bean
        fun hostSystemViewProvider(): SystemViewProvider = SystemViewProvider { _, _ ->
            Flux.just(
                SystemViews.of("host-open", "orders", "Open", JsonSerializer.createObjectNode().put("kind", "record"))
            )
        }
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private val client: WebTestClient by lazy {
        val port = applicationContext.environment.getRequiredProperty("local.server.port")
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .codecs { it.defaultCodecs().maxInMemorySize(OPENAPI_BUFFER_BYTES) }
            .build()
    }

    @Test
    fun `adds its beans beside the host's and keeps the host's single ones`() {
        applicationContext.getBean(ViewStoreQueryPolicy::class.java).assert().isNotNull()
        applicationContext.getBean(ViewStoreAppIdHeaderAppender::class.java).assert().isNotNull()
        applicationContext.getBean(ViewStoreWebFilter::class.java).assert().isNotNull()
        applicationContext.getBean(
            SharedBoardReferences::class.java
        ).assert().isInstanceOf(SnapshotSharedBoardReferences::class.java)
        applicationContext.containsBean(ViewStoreAutoConfiguration.ROUTER_FUNCTION_BEAN_NAME).assert().isTrue()
        applicationContext.getBeansOfType(CommandBuilderExtractor::class.java).assert().hasSize(1)
        applicationContext.getBeansOfType(CommandMessageExtractor::class.java).assert().hasSize(1)
    }

    @Test
    fun `a host's system view provider replaces the configured one`() {
        applicationContext.getBeansOfType(
            SystemViewProvider::class.java
        ).keys.assert().containsExactly("hostSystemViewProvider")
        client.get().uri("/view-store/tenant/t1/owner/(shared)/system-views")
            .header(ViewStoreService.APP_ID_HEADER, "console").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$[0].id").isEqualTo("host-open")
        client.put().uri("/view-store/tenant/t1/owner/(shared)/view/host-open/rename")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"title":"Mine"}""").exchange()
            .expectStatus().isForbidden
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY)
    }

    @Test
    fun `the host's own routes stay where they were`() {
        val paths = client.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
            .get("paths").propertyNames().toList()
        paths.assert().contains(
            "/owner/{ownerId}/cart/add_cart_item",
            "/view-store/tenant/{tenantId}/owner/{ownerId}/view",
            "/view-store/tenant/{tenantId}/owner/{ownerId}/view/snapshot/list",
            "/view-store/tenant/{tenantId}/owner/{ownerId}/system-views",
        )
    }

    @Test
    fun `a command needs the application it is written in`() {
        client.post().uri("/view-store/tenant/t1/owner/alice/view")
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"definitionId":"orders","title":"Mine","config":{"kind":"record"}}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
    }

    @Test
    fun `stays out when disabled`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ViewStoreAutoConfiguration::class.java))
            .withPropertyValues("wow.view-store.enabled=false")
            .run { context ->
                context.containsBean("viewStoreQueryPolicy").assert().isFalse()
                context.containsBean(ViewStoreAutoConfiguration.ROUTER_FUNCTION_BEAN_NAME).assert().isFalse()
            }
    }
}

private const val OPENAPI_BUFFER_BYTES = 16 * 1024 * 1024
