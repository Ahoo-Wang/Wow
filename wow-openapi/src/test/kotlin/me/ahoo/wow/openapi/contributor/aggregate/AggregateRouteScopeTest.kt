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

package me.ahoo.wow.openapi.contributor.aggregate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test

class AggregateRouteScopeTest {
    private val exampleContext = MaterializedNamedBoundedContext("example-service")

    private fun scope(aggregateType: Class<*>, contextName: String = "example-service") =
        AggregateRouteScope(MaterializedNamedBoundedContext(contextName), aggregateType.aggregateRouteMetadata())

    private val route = AggregateRoute(
        handlerKey = "handler",
        resourceName = "snapshot",
        operation = "count",
        summary = "Count Snapshot",
        pathSuffix = "snapshot/count",
        responses = listOf(HttpResponse(Https.Code.OK))
    )

    @Test
    fun `an aggregate with a tenant and an owner is published under four variants`() {
        val scope = scope(Order::class.java)

        scope.defaultAppendTenantPath.assert().isTrue()
        scope.defaultAppendOwnerPath.assert().isTrue()
        scope.defaultAppendIdPath.assert().isTrue()
        scope.tenantOwnerVariants.assert().containsExactly(
            TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = false),
            TenantOwnerVariant(appendTenantPath = true, appendOwnerPath = false),
            TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = true),
            TenantOwnerVariant(appendTenantPath = true, appendOwnerPath = true)
        )
    }

    @Test
    fun `a static tenant drops the tenant variants and an aggregate id owner drops the id path`() {
        val scope = scope(Cart::class.java)

        scope.defaultAppendTenantPath.assert().isFalse()
        scope.defaultAppendIdPath.assert().isFalse()
        scope.tenantOwnerVariants.assert().containsExactly(
            TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = false),
            TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = true)
        )
    }

    @Test
    fun `an aggregate without an owner has no owner variants`() {
        val scope = scope(MockCommandAggregate::class.java)

        scope.defaultAppendOwnerPath.assert().isFalse()
        scope.tenantOwnerVariants.map { it.appendOwnerPath }.assert().containsOnly(false)
    }

    @Test
    fun `path should prefix the context alias of another bounded context`() {
        scope(Order::class.java).path(appendTenantPath = true, appendOwnerPath = true, appendIdPath = true, "pay")
            .assert().isEqualTo("/tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/pay")
        scope(Order::class.java, "other-service").path(false, false, false)
            .assert().isEqualTo("/example/sales-order")
    }

    @Test
    fun `parameters should follow the appended path segments, then the space id header`() {
        scope(Order::class.java).parameters(appendTenantPath = true, appendOwnerPath = true, appendIdPath = true)
            .assert().containsExactly(
                CommonComponents.tenantIdPathParameter,
                CommonComponents.ownerIdPathParameter,
                CommonComponents.idPathParameter,
                CommonComponents.spaceIdHeaderParameter
            )
        scope(Order::class.java).parameters(false, false, false).assert()
            .containsExactly(CommonComponents.spaceIdHeaderParameter)
    }

    @Test
    fun `tenant owner naming should name both scopes in the route id and summary`() {
        val scope = AggregateRouteScope(exampleContext, Order::class.java.aggregateRouteMetadata())
        val contract = scope.contract(
            route.copy(parameters = listOf(CommonComponents.versionPathParameter))
                .within(TenantOwnerVariant(appendTenantPath = true, appendOwnerPath = true))
        )

        contract.routeId.assert().isEqualTo("example.order.tenant.owner.snapshot.count")
        contract.summary.assert().isEqualTo("Count Snapshot Within Tenant Owner")
        contract.path.assert().isEqualTo("/tenant/{tenantId}/owner/{ownerId}/sales-order/snapshot/count")
        contract.parameters.assert().containsExactly(
            CommonComponents.tenantIdPathParameter,
            CommonComponents.ownerIdPathParameter,
            CommonComponents.spaceIdHeaderParameter,
            CommonComponents.versionPathParameter
        )
        contract.tags.map { it.name }.assert().contains("example.order")
        contract.handlerMetadata.assert().isInstanceOf(HttpRouteHandlerMetadata.Aggregate::class.java)
    }

    @Test
    fun `tenant naming should leave the owner out of the route id and summary`() {
        val contract = scope(Order::class.java).contract(
            route.copy(appendTenantPath = true, appendOwnerPath = true, naming = ScopeNaming.TENANT)
        )

        contract.routeId.assert().isEqualTo("example.order.tenant.snapshot.count")
        contract.summary.assert().isEqualTo("Count Snapshot")
        contract.path.assert().isEqualTo("/tenant/{tenantId}/owner/{ownerId}/sales-order/snapshot/count")
    }

    @Test
    fun `an unscoped route keeps its summary`() {
        val contract = scope(Order::class.java).contract(route)

        contract.routeId.assert().isEqualTo("example.order.snapshot.count")
        contract.summary.assert().isEqualTo("Count Snapshot")
        contract.method.assert().isEqualTo(Https.Method.POST)
        contract.accept.assert().isEqualTo(JSON_ACCEPT)
    }
}
