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

package me.ahoo.wow.webflux.route.identity

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.identity.RouteIdentitySource.HEADER
import me.ahoo.wow.webflux.route.identity.RouteIdentitySource.NONE
import me.ahoo.wow.webflux.route.identity.RouteIdentitySource.OWNER
import me.ahoo.wow.webflux.route.identity.RouteIdentitySource.PATH
import me.ahoo.wow.webflux.route.identity.RouteIdentitySource.STATIC
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Each route's identity binding is computed from its contract: the generated routes of the example aggregates
 * (`sales-order`: owned, spaced, no static tenant; `cart`: static tenant, owner = aggregate ID, not spaced) take each
 * fact from the source below.
 */
class RouteIdentityBindingTest {
    private val routes: List<HttpRouteContract> =
        RouterSpecs(MaterializedNamedBoundedContext("example-service")).build().toRouteCatalog().routes

    private fun bindingOf(method: String, path: String): RouteIdentityBinding {
        val contract = routes.single { it.method == method && it.path == path }
        val routeMetadata = when {
            "/sales-order" in path -> Order::class.java.aggregateRouteMetadata()
            else -> Cart::class.java.aggregateRouteMetadata()
        }
        return RouteIdentity.of(contract).binding(routeMetadata)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "DELETE, /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}, PATH, PATH, PATH, HEADER",
        "POST, /tenant/{tenantId}/sales-order/{id}/pay, PATH, HEADER, PATH, HEADER",
        "POST, /sales-order/snapshot/count, HEADER, HEADER, HEADER, HEADER",
        "POST, /owner/{ownerId}/sales-order/event/count, HEADER, PATH, HEADER, HEADER",
        "GET, /tenant/{tenantId}/sales-order/{id}/event/{headVersion}/{tailVersion}, PATH, HEADER, PATH, HEADER",
        "POST, /owner/{ownerId}/cart/add_cart_item, STATIC, PATH, OWNER, NONE",
        "GET, /owner/{ownerId}/cart/state, STATIC, PATH, OWNER, NONE",
        // The owner of a cart is its ID, which this path states.
        "GET, /cart/{id}/event/{headVersion}/{tailVersion}, STATIC, PATH, PATH, NONE",
        "POST, /cart/snapshot/count, STATIC, HEADER, OWNER, NONE",
    )
    fun `each fact comes from the source the route contract declares`(
        method: String,
        path: String,
        tenant: RouteIdentitySource,
        owner: RouteIdentitySource,
        aggregateId: RouteIdentitySource,
        space: RouteIdentitySource,
    ) {
        val binding = bindingOf(method, path)
        listOf(binding.tenantId.source, binding.ownerId.source, binding.aggregateId.source, binding.spaceId.source)
            .assert().containsExactly(tenant, owner, aggregateId, space)
    }

    @Test
    fun `a route binding names its path variables and headers`() {
        val binding = bindingOf("DELETE", "/tenant/{tenantId}/owner/{ownerId}/sales-order/{id}")
        binding.pathVariables.assert()
            .containsExactlyInAnyOrder(MessageRecords.TENANT_ID, MessageRecords.OWNER_ID, MessageRecords.ID)
        binding.tenantId.assert()
            .isEqualTo(FactBinding(PATH, MessageRecords.TENANT_ID, listOf(CommandComponent.Header.TENANT_ID)))
        binding.spaceId.assert().isEqualTo(FactBinding(HEADER, headers = listOf(CommonComponent.Header.SPACE_ID)))
        // A static tenant ignores the tenant header, as in 9.2: no header to check against it.
        bindingOf("POST", "/owner/{ownerId}/cart/add_cart_item").tenantId.assert()
            .isEqualTo(FactBinding(STATIC, Cart::class.java.aggregateRouteMetadata().aggregateMetadata.staticTenantId))
    }

    @Test
    fun `header aliases follow Wow's own headers`() {
        val binding = RouteIdentityBinding.of(
            pathVariables = emptySet(),
            staticTenantId = null,
            ownerPolicy = OwnerPolicy.NEVER,
            spaced = true,
            aliases = IdentityHeaderAliases(spaceId = listOf("X-Space"), requestId = listOf("X-Request")),
        )
        binding.spaceId.headers.assert().containsExactly(CommonComponent.Header.SPACE_ID, "X-Space")
        binding.requestId.headers.assert().containsExactly(CommandComponent.Header.REQUEST_ID, "X-Request")

        val aliasOnly = MockServerRequest.builder()
            .header(CommonComponent.Header.SPACE_ID, " ")
            .header("X-Space", "alias-space")
            .header("X-Request", "alias-request")
            .build()
        binding.spaceId(aliasOnly).assert().isEqualTo("alias-space")
        binding.requestId(aliasOnly).assert().isEqualTo("alias-request")

        val both = MockServerRequest.builder()
            .header(CommonComponent.Header.SPACE_ID, "wow-space")
            .header("X-Space", "alias-space")
            .header(CommandComponent.Header.REQUEST_ID, "wow-request")
            .header("X-Request", "alias-request")
            .build()
        binding.spaceId(both).assert().isEqualTo("wow-space")
        binding.requestId(both).assert().isEqualTo("wow-request")
    }

    @Test
    fun `aliases merge in order without duplicates`() {
        val merged = IdentityHeaderAliases.merge(
            listOf(
                IdentityHeaderAliases(spaceId = listOf("A")),
                IdentityHeaderAliases(spaceId = listOf("B", "A"), requestId = listOf("R")),
            )
        )
        merged.spaceId.assert().containsExactly("A", "B")
        merged.requestId.assert().containsExactly("R")
        IdentityHeaderAliases.NONE.isEmpty().assert().isTrue()
    }

    /**
     * An aggregate owned by its ID, on a route that states `{id}` but not `{ownerId}`: before 9.3.0 the
     * `Command-Owner-Id` header replaced the path's ID. The path wins, and a header that contradicts it is rejected.
     */
    @Test
    fun `on an aggregate owned by its ID the path id wins over the owner header`() {
        val binding = RouteIdentityBinding.of(
            pathVariables = setOf(MessageRecords.ID),
            staticTenantId = null,
            ownerPolicy = OwnerPolicy.AGGREGATE_ID,
            spaced = false,
        )
        val withoutHeader = MockServerRequest.builder().pathVariable(MessageRecords.ID, "cart-1").build()
        binding.aggregateId(withoutHeader).assert().isEqualTo("cart-1")
        binding.ownerId(withoutHeader).assert().isEqualTo("cart-1")
        // A read does not filter by an owner derived from the ID: an in-process cart may store a blank owner.
        binding.readOwnerId(withoutHeader).assert().isNull()

        val agreeing = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, "cart-1")
            .header(CommandComponent.Header.OWNER_ID, "cart-1")
            .build()
        binding.aggregateId(agreeing).assert().isEqualTo("cart-1")
        binding.readOwnerId(agreeing).assert().isEqualTo("cart-1")
    }

    @Test
    fun `a binding read outside a materialized router follows the matched path variables`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
            .pathVariable("other", "x")
            .build()
        RouteIdentity.of(request).pathVariables.assert().containsExactly(MessageRecords.TENANT_ID)
        RouteIdentity.of(request).binding(Order::class.java.aggregateRouteMetadata()).tenantId.source
            .assert().isEqualTo(PATH)
        RouteIdentity.of(MockServerRequest.builder().build())
            .binding(Order::class.java.aggregateRouteMetadata()).spaceId.source.assert().isEqualTo(HEADER)
        RouteIdentity.of(MockServerRequest.builder().build())
            .binding(Cart::class.java.aggregateRouteMetadata()).spaceId.source.assert().isEqualTo(NONE)
        RouteIdentity.of(MockServerRequest.builder().build())
            .binding(Cart::class.java.aggregateRouteMetadata()).aggregateId.source.assert().isEqualTo(OWNER)
    }

    @Test
    fun `an unrouted identity is shared by the requests that match the same identity path variables`() {
        fun request(vararg variables: String) = MockServerRequest.builder().apply {
            variables.forEach { pathVariable(it, "value") }
        }.pathVariable("other", "x").build()

        val tenantAndId = RouteIdentity.of(request(MessageRecords.TENANT_ID, MessageRecords.ID))
        tenantAndId.pathVariables.assert().containsExactly(MessageRecords.TENANT_ID, MessageRecords.ID)
        RouteIdentity.of(request(MessageRecords.ID, MessageRecords.TENANT_ID)).assert().isSameAs(tenantAndId)
        val all = RouteIdentity.of(request(MessageRecords.TENANT_ID, MessageRecords.OWNER_ID, MessageRecords.ID))
        all.pathVariables.assert()
            .containsExactly(MessageRecords.TENANT_ID, MessageRecords.OWNER_ID, MessageRecords.ID)
        RouteIdentity.of(request()).pathVariables.assert().isEmpty()
        RouteIdentity.of(request()).aliases.assert().isSameAs(IdentityHeaderAliases.NONE)

        val aliases = IdentityHeaderAliases(spaceId = listOf("X-Space"))
        val aliased = request(MessageRecords.OWNER_ID)
        RouteIdentity.withAliases(aliased, aliases)
        val aliasedIdentity = RouteIdentity.of(aliased)
        aliasedIdentity.aliases.assert().isSameAs(aliases)
        aliasedIdentity.pathVariables.assert().containsExactly(MessageRecords.OWNER_ID)
        val second = request(MessageRecords.OWNER_ID)
        RouteIdentity.withAliases(second, aliases)
        RouteIdentity.of(second).assert().isSameAs(aliasedIdentity)
        RouteIdentity.of(request(MessageRecords.OWNER_ID)).assert().isNotSameAs(aliasedIdentity)
    }

    @Test
    fun `the binding of another aggregate is computed once per aggregate policy`() {
        val routeIdentity = RouteIdentity(setOf(MessageRecords.ID))
        val order = Order::class.java.aggregateRouteMetadata()
        val cart = Cart::class.java.aggregateRouteMetadata()
        val orderBinding = routeIdentity.binding(order)
        routeIdentity.binding(order).assert().isSameAs(orderBinding)
        routeIdentity.binding(order.aggregateMetadata).assert().isSameAs(orderBinding)
        orderBinding.assert().isEqualTo(RouteIdentityBinding.of(setOf(MessageRecords.ID), order))
        val cartBinding = routeIdentity.binding(cart)
        routeIdentity.binding(cart).assert().isSameAs(cartBinding)
        cartBinding.assert().isEqualTo(RouteIdentityBinding.of(setOf(MessageRecords.ID), cart))
        cartBinding.tenantId.source.assert().isEqualTo(STATIC)
    }

    @Test
    fun `a request identity reads the owner once and still checks every body against it`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, "owner-a")
            .header(CommandComponent.Header.AGGREGATE_ID, "victim")
            .build()
        val identity = request.identity(Cart::class.java.aggregateRouteMetadata())
        identity.ownerId(body = "owner-a").assert().isEqualTo("owner-a")
        identity.ownerId().assert().isEqualTo("owner-a")
        // The cart's ID is its owner.
        identity.aggregateId().assert().isEqualTo("owner-a")
        assertThrownBy<IllegalArgumentException> {
            identity.ownerId(body = "victim")
        }.hasMessage("Conflicting ownerId: the route fixes [owner-a], but the command body gives [victim].")

        val withoutOwner = MockServerRequest.builder()
            .header(CommandComponent.Header.AGGREGATE_ID, "cart-h")
            .build()
            .identity(Cart::class.java.aggregateRouteMetadata())
        withoutOwner.aggregateId().assert().isEqualTo("cart-h")
        withoutOwner.ownerId().assert().isNull()
    }

    @Test
    fun `only identity path variables can be required`() {
        val values = IdentityPathValues(tenantId = "tenant-a", ownerId = null, id = " ")
        values.require(MessageRecords.TENANT_ID).assert().isEqualTo("tenant-a")
        assertThrownBy<IllegalArgumentException> { values.require(MessageRecords.OWNER_ID) }
            .hasMessage("Path variable [ownerId] must not be blank.")
        assertThrownBy<IllegalArgumentException> { values.require(MessageRecords.ID) }
            .hasMessage("Path variable [id] must not be blank.")
        assertThrownBy<IllegalStateException> { values.require("other") }
            .hasMessage("[other] is not an identity path variable.")
        values["other"].assert().isNull()
    }

    private fun orderBinding(request: ServerRequest): RouteIdentityBinding =
        RouteIdentity.of(request).binding(Order::class.java.aggregateRouteMetadata())

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t"])
    fun `a declared but blank path variable is rejected instead of falling back to the header`(blank: String) {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, blank)
            .pathVariable(MessageRecords.OWNER_ID, blank)
            .pathVariable(MessageRecords.ID, blank)
            .header(CommandComponent.Header.TENANT_ID, "victim")
            .header(CommandComponent.Header.OWNER_ID, "victim")
            .header(CommandComponent.Header.AGGREGATE_ID, "victim")
            .build()
        val binding = orderBinding(request)

        assertThrownBy<IllegalArgumentException> { binding.tenantId(request) }
            .hasMessage("Path variable [tenantId] must not be blank.")
        assertThrownBy<IllegalArgumentException> { binding.ownerId(request) }
            .hasMessage("Path variable [ownerId] must not be blank.")
        assertThrownBy<IllegalArgumentException> { binding.aggregateId(request) }
            .hasMessage("Path variable [id] must not be blank.")
    }

    @Test
    fun `a declared path variable wins over a header that agrees or is absent`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
            .pathVariable(MessageRecords.OWNER_ID, "owner-a")
            .pathVariable(MessageRecords.ID, "id-a")
            .header(CommandComponent.Header.TENANT_ID, "tenant-a")
            // The aggregate ID is not a fact a header may contradict: the path wins, the header is ignored.
            .header(CommandComponent.Header.AGGREGATE_ID, "victim")
            .build()
        val binding = orderBinding(request)

        binding.tenantId(request).assert().isEqualTo("tenant-a")
        binding.ownerId(request).assert().isEqualTo("owner-a")
        binding.aggregateId(request).assert().isEqualTo("id-a")
    }

    @Test
    fun `a header contradicting a declared tenant or owner path variable is rejected`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
            .pathVariable(MessageRecords.OWNER_ID, "owner-a")
            .header(CommandComponent.Header.TENANT_ID, "victim")
            .header(CommandComponent.Header.OWNER_ID, "victim")
            .build()
        val binding = orderBinding(request)

        assertThrownBy<IllegalArgumentException> {
            binding.tenantId(request)
        }.hasMessage("Conflicting tenantId: the route fixes [tenant-a], but the request header gives [victim].")
        assertThrownBy<IllegalArgumentException> {
            binding.ownerId(request)
        }.hasMessage("Conflicting ownerId: the route fixes [owner-a], but the request header gives [victim].")
    }

    @Test
    fun `a route without the variable reads the header`() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.TENANT_ID, "tenant-h")
            .header(CommandComponent.Header.OWNER_ID, "owner-h")
            .header(CommandComponent.Header.AGGREGATE_ID, "id-h")
            .header(CommonComponent.Header.SPACE_ID, "space-h")
            .build()
        val binding = orderBinding(request)

        binding.tenantId(request).assert().isEqualTo("tenant-h")
        binding.ownerId(request).assert().isEqualTo("owner-h")
        binding.aggregateId(request).assert().isEqualTo("id-h")
        binding.spaceId(request).assert().isEqualTo("space-h")
    }
}
