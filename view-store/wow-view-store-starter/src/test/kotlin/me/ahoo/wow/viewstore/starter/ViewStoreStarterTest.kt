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
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.configuration.namedAggregate
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.rest.RoutePaths
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.SystemViewSource
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.starter.system.StoredSystemViewSource
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.viewstore.starter.system.SystemViews
import me.ahoo.wow.webflux.route.command.extractor.CommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import me.ahoo.wow.webflux.route.query.QueryRequestScope
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
import java.time.Duration

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

        /** Snapshots are in memory here, without a query backend: the host serves its stored views itself. */
        @Bean
        fun hostStoredSystemViewSource(): StoredSystemViewSource = StoredSystemViewSource { _, _, _ ->
            Flux.just(
                SystemViews.of("stored-1", "orders", "Stored", JsonSerializer.createObjectNode().put("kind", "record"))
                    .copy(source = SystemViewSource.STORED, version = 2)
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

    /**
     * The first `/v3/api-docs` builds the whole document: springdoc scans the routes and every command, query and
     * response schema is generated, on the request. That takes about 0.2 s on a warm machine but several seconds on a
     * busy CI runner, past WebTestClient's default 5 s response timeout; later requests are served from springdoc's
     * cache. Only this request gets the longer budget.
     */
    private val openApiClient: WebTestClient by lazy {
        client.mutate().responseTimeout(OPENAPI_RESPONSE_TIMEOUT).build()
    }

    @Test
    fun `adds its beans beside the host's and keeps the host's single ones`() {
        applicationContext.getBean(ViewStoreQueryPolicy::class.java).assert().isNotNull()
        applicationContext.getBean(ViewStoreScopeContributor::class.java).assert().isNotNull()
        // The view store adds its scope as a contributor, so the host's scope provider stays the host's.
        applicationContext.getBeansOfType(QueryRequestScope::class.java).assert().hasSize(1)
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
            .expectBody()
            .jsonPath("$[0].id").isEqualTo("host-open")
            .jsonPath("$[0].source").isEqualTo("configured")
            .jsonPath("$[1].id").isEqualTo("stored-1")
            .jsonPath("$[1].source").isEqualTo("stored")
            .jsonPath("$[1].version").isEqualTo(2)
        client.put().uri("/view-store/tenant/t1/owner/(shared)/view/host-open/rename")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"title":"Mine"}""").exchange()
            .expectStatus().isForbidden
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY)
    }

    @Test
    fun `the host's own routes stay where they were`() {
        val document = openApiClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        val paths = document.get("paths").propertyNames().toList()
        // The host's delete keeps Wow's own command schema: the view store deletes with a command of its own, so it
        // does not claim DefaultDeleteAggregate (which would name the schema after the view store in the host).
        document.at("/paths/~1owner~1{ownerId}~1cart/delete/requestBody/content/application~1json/schema/\$ref")
            .asString().assert().isEqualTo("#/components/schemas/wow.api.command.DefaultDeleteAggregate")
        document.at(
            "/paths/~1view-store~1tenant~1{tenantId}~1owner~1{ownerId}~1view~1{id}/delete/requestBody/content/" +
                "application~1json/schema/\$ref"
        ).asString().assert().isEqualTo("#/components/schemas/view-store.view.DeleteView")
        paths.assert().contains(
            "/owner/{ownerId}/cart/add_cart_item",
            "/view-store/tenant/{tenantId}/owner/{ownerId}/view",
            "/view-store/tenant/{tenantId}/owner/{ownerId}/view/snapshot/list",
            "/view-store/tenant/{tenantId}/owner/{ownerId}/system-views",
        )
        val systemView = document.at("/components/schemas").propertyNames().single { it.endsWith(".SystemView") }
        document.at("/components/schemas/$systemView/properties/source/\$ref").asString()
            .assert().isEqualTo("#/components/schemas/${systemView}Source")
        document.at("/components/schemas/${systemView}Source/enum").toString()
            .assert().isEqualTo("""["configured","stored"]""")
        document.at("/components/schemas/$systemView/properties/version").isMissingNode.assert().isFalse()
    }

    /**
     * The document shows only what is served: the routes [ViewStoreRouteGuard] closes are taken out of it after Wow's
     * customizer has merged them, and every open one stays. This host's component scan covers the starter's package,
     * so the view store's customizer is registered before Wow's: only their orders put it after.
     */
    @Test
    fun `the document leaves out the routes the view store closes`() {
        val document = openApiClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        val documented = document.get("paths").properties().flatMap { (path, item) ->
            item.propertyNames().filter { it in HTTP_METHODS }.map { "${it.uppercase()} $path" }
        }.toSet()
        val guard = applicationContext.getBean(ViewStoreRouteGuard::class.java)
        guard.closedContracts.assert().isNotEmpty()
        documented.intersect(guard.closedContracts.map { "${it.method} ${it.path}" }.toSet()).assert().isEmpty()
        documented.assert().containsAll(guard.openContracts.map { "${it.method} ${it.path}" })
        val scope = "/view-store/tenant/{tenantId}/owner/{ownerId}"
        documented.assert()
            .doesNotContain("DELETE $scope/view_preferences/{id}", "PUT $scope/view/{id}/recover")
            .contains("POST $scope/view", "PUT $scope/view/{id}/claim", "GET $scope/system-views")
    }

    /**
     * The view store refuses its own commands on the command facade, but `DefaultDeleteAggregate` is Wow's, not the
     * view store's (the view store deletes with `DeleteView`): the host's delete through the facade reaches the host.
     */
    @Test
    fun `the host's delete through the command facade reaches the host's aggregate`() {
        // No aggregate claims Wow's delete command: the facade names the aggregate from the headers alone.
        DefaultDeleteAggregate::class.java.namedAggregate().assert().isNull()
        val cartId = "facade-delete-cart"
        client.post().uri("/owner/$cartId/cart/add_cart_item")
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"productId":"p1","quantity":1}""")
            .exchange()
            .expectStatus().isOk
        client.post().uri(RoutePaths.COMMAND_SEND)
            .header(CommandHeaders.COMMAND_TYPE, DefaultDeleteAggregate::class.java.name)
            .header(CommandHeaders.COMMAND_AGGREGATE_CONTEXT, "example-service")
            .header(CommandHeaders.COMMAND_AGGREGATE_NAME, "cart")
            .header(CommandHeaders.AGGREGATE_ID, cartId)
            .header(CommandHeaders.OWNER_ID, cartId)
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.aggregateName").isEqualTo("cart")
            .jsonPath("$.aggregateId").isEqualTo(cartId)
            .jsonPath("$.errorCode").isEqualTo("Ok")
        client.get().uri("/owner/$cartId/cart/$cartId/state")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `a command needs the application it is written in`() {
        client.post().uri("/view-store/tenant/t1/owner/alice/view")
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
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
                context.containsBean("viewStoreScopeContributor").assert().isFalse()
                context.containsBean(ViewStoreAutoConfiguration.ROUTER_FUNCTION_BEAN_NAME).assert().isFalse()
            }
    }
}

private const val OPENAPI_BUFFER_BYTES = 16 * 1024 * 1024
private val OPENAPI_RESPONSE_TIMEOUT: Duration = Duration.ofMinutes(1)
private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
