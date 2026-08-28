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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.disable.DisabledRouteAggregate
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

internal class ExampleDomainOpenAPITest {

    private val namedContext = MaterializedNamedBoundedContext("example-service")
    private lateinit var routerSpecs: RouterSpecs
    private lateinit var openAPI: OpenAPI

    @BeforeEach
    fun setUp() {
        routerSpecs = RouterSpecs(namedContext).build()
        openAPI = OpenAPI()
        routerSpecs.mergeOpenAPIFromCatalog(openAPI)
    }

    @Nested
    inner class RouterSpecsBuild {

        @Test
        fun `should discover order and cart aggregates`() {
            val aggregateTypes = MetadataSearcher.namedAggregateType
            aggregateTypes.values.assert().contains(Order::class.java)
            aggregateTypes.values.assert().contains(Cart::class.java)
        }

        @Test
        fun `should not generate routes for disabled route aggregate`() {
            val disabledPaths = catalogRoutes().filter {
                it.path.contains("disabled_route_aggregate")
            }
            disabledPaths.assert().isEmpty()
            MetadataSearcher.namedAggregateType.values.assert().contains(DisabledRouteAggregate::class.java)
        }

        @Test
        fun `should generate expected route count`() {
            catalogRoutes().assert().hasSizeGreaterThanOrEqualTo(20)
        }

        @Test
        fun `should set info title to context name`() {
            openAPI.info.assert().isNotNull()
            openAPI.info.title.assert().isEqualTo("example-service")
        }
    }

    @Nested
    inner class AggregateRoutes {

        @Test
        fun `should publish static aggregate fields through aggregate request bodies`() {
            val fieldsKey = "example.cart.CartAggregatedFields"
            val fieldsRef = "#/components/schemas/$fieldsKey"
            val fieldsSchema = openAPI.components.schemas.getValue(fieldsKey)

            fieldsSchema.type.assert().isEqualTo("string")
            fieldsSchema.`enum`.assert()
                .contains("aggregateId", "state", "state.items.productId")
                .doesNotContain("")
            listOf("AggregationQuery", "CountQuery", "ListQuery", "PagedQuery", "SingleQuery").forEach { queryType ->
                val requestBody = openAPI.components.requestBodies.getValue("example.cart.$queryType")
                val queryFields = requestBody.extensions.getValue("x-wow-query-fields") as Schema<*>
                queryFields.`$ref`.assert().isEqualTo(fieldsRef)
            }
        }

        @Test
        fun `snapshot aggregation should use generic query body and expose dynamic rows`() {
            val requestBody = requireNotNull(openAPI.components.requestBodies["example.cart.AggregationQuery"])
            val responseSchema = requireNotNull(
                openAPI.paths["/cart/snapshot/aggregation"]
                    ?.post
                    ?.responses
                    ?.get("200")
                    ?.content
                    ?.get(Https.MediaType.APPLICATION_JSON)
                    ?.schema
            )
            requestBody.content[Https.MediaType.APPLICATION_JSON]!!.schema.`$ref`
                .assert().isEqualTo("#/components/schemas/wow.api.query.AggregationQuery")
            val querySchema = requireNotNull(openAPI.components.schemas["wow.api.query.AggregationQuery"])
            val logicalFieldRef = "#/components/schemas/wow.api.query.LogicalField"
            openAPI.components.schemas.getValue("wow.api.query.LogicalField").types
                .assert().contains("string").doesNotContain("object")
            querySchema.properties.getValue("filter").`$ref`
                .assert().isEqualTo("#/components/schemas/wow.api.query.FilterExpression")
            val elementSchema = openAPI.components.schemas.getValue("wow.api.query.AggregationElement")
            assertAggregationExpressionSchema()
            listOf(
                elementSchema.properties.getValue("path"),
                openAPI.components.schemas.getValue("wow.api.query.AggregationExpression.Field")
                    .properties.getValue("field"),
                openAPI.components.schemas.getValue("wow.api.query.AggregationGroup.DateHistogram")
                    .properties.getValue("field"),
                openAPI.components.schemas.getValue("wow.api.query.AggregationGroup.Histogram")
                    .properties.getValue("field"),
                openAPI.components.schemas.getValue("wow.api.query.AggregationGroup.Terms")
                    .properties.getValue("field"),
            ).forEach { fieldSchema ->
                fieldSchema.`$ref`.assert().isEqualTo(logicalFieldRef)
            }
            querySchema.required.assert().containsExactly("metrics")
            querySchema.properties.getValue("metrics").minItems.assert().isEqualTo(1)
            querySchema.properties.getValue("metrics").maxItems.assert().isEqualTo(64)
            assertAggregationMetricSchema(querySchema, logicalFieldRef)
            querySchema.properties.getValue("elements").maxItems.assert().isEqualTo(5)
            querySchema.properties.getValue("limit").minimum.assert().isEqualTo(BigDecimal.ONE)
            querySchema.properties.getValue("limit").maximum.intValueExact().assert().isEqualTo(10_000)
            querySchema.additionalProperties.assert().isEqualTo(false)
            elementSchema.required.assert().containsExactly("path")
            elementSchema.additionalProperties.assert().isEqualTo(false)
            responseSchema.type.assert().isEqualTo("array")
            responseSchema.items.type.assert().isEqualTo("object")
            (responseSchema.items.additionalProperties as Schema<*>).nullable.assert().isTrue()
            val aggregationRouteIds = catalogRoutes()
                .filter { it.routeId.endsWith(".snapshot.aggregation") }
                .map { it.routeId }
            val countRouteIds = catalogRoutes()
                .filter { it.routeId.endsWith(".snapshot.count") }
                .map { it.routeId.removeSuffix("count") + "aggregation" }
            aggregationRouteIds.assert().containsExactlyInAnyOrder(*countRouteIds.toTypedArray())
        }

        private fun assertAggregationMetricSchema(querySchema: Schema<*>, logicalFieldRef: String) {
            val metricSchema = openAPI.components.schemas.getValue("wow.api.query.AggregationMetric")
            querySchema.properties.getValue("metrics").items.`$ref`.assert()
                .isEqualTo("#/components/schemas/wow.api.query.AggregationMetric")
            metricSchema.oneOf.map { it.`$ref` }.assert().containsExactlyInAnyOrder(
                "#/components/schemas/wow.api.query.AggregationMetric.Count",
                "#/components/schemas/wow.api.query.AggregationMetric.Numeric",
                "#/components/schemas/wow.api.query.AggregationMetric.Any",
            )
            metricSchema.discriminator.propertyName.assert().isEqualTo("type")
            (metricSchema.properties.getValue("alias").readOnly == true).assert().isFalse()
            val anySchema = openAPI.components.schemas.getValue("wow.api.query.AggregationMetric.Any")
            anySchema.required.assert().containsExactlyInAnyOrder("field", "alias", "type")
            anySchema.properties.getValue("field").`$ref`.assert().isEqualTo(logicalFieldRef)
        }

        private fun assertAggregationExpressionSchema() {
            val expressionRef = "#/components/schemas/wow.api.query.AggregationExpression"
            openAPI.components.schemas.getValue("wow.api.query.AggregationMetric.Numeric")
                .properties.getValue("expression").`$ref`.assert().isEqualTo(expressionRef)
            val expressionSchema = openAPI.components.schemas.getValue("wow.api.query.AggregationExpression")
            expressionSchema.oneOf.map { it.`$ref` }.assert().containsExactlyInAnyOrder(
                "#/components/schemas/wow.api.query.AggregationExpression.Field",
                "#/components/schemas/wow.api.query.AggregationExpression.Constant",
                "#/components/schemas/wow.api.query.AggregationExpression.Binary",
            )
            expressionSchema.anyOf.assert().isNull()
            expressionSchema.discriminator.propertyName.assert().isEqualTo("type")
            val binarySchema = openAPI.components.schemas.getValue("wow.api.query.AggregationExpression.Binary")
            binarySchema.properties.getValue("left").`$ref`.assert().isEqualTo(expressionRef)
            binarySchema.properties.getValue("right").`$ref`.assert().isEqualTo(expressionRef)
            openAPI.components.schemas.getValue("wow.api.query.AggregationExpression.Constant")
                .properties.getValue("value").format.assert().isEqualTo("double")
        }

        @Test
        fun `snapshot aggregation event stream should expose SSE envelopes`() {
            val eventStreamSchema = requireNotNull(
                openAPI.paths["/cart/snapshot/aggregation"]
                    ?.post
                    ?.responses
                    ?.get("200")
                    ?.content
                    ?.get(Https.MediaType.TEXT_EVENT_STREAM)
                    ?.schema
            )

            eventStreamSchema.items.properties.assert().containsKey("data")
            eventStreamSchema.items.required.assert().contains("data")
        }

        @Test
        fun `event aggregation should use event query conventions`() {
            val operation = requireNotNull(openAPI.paths["/cart/event/aggregation"]?.post)

            operation.operationId.assert().isEqualTo("example.cart.event.aggregation")
            operation.requestBody.`$ref`.assert()
                .isEqualTo("#/components/requestBodies/wow.AggregationQuery")
            operation.responses.keys.assert().containsExactly("200")

            val jsonSchema = requireNotNull(
                operation.responses["200"]
                    ?.content
                    ?.get(Https.MediaType.APPLICATION_JSON)
                    ?.schema
            )
            jsonSchema.type.assert().isEqualTo("array")
            jsonSchema.items.type.assert().isEqualTo("object")

            val eventStreamSchema = requireNotNull(
                operation.responses["200"]
                    ?.content
                    ?.get(Https.MediaType.TEXT_EVENT_STREAM)
                    ?.schema
            )
            eventStreamSchema.items.properties.assert().containsKey("data")
            eventStreamSchema.items.required.assert().contains("data")

            val aggregationRouteIds = catalogRoutes()
                .filter { it.routeId.endsWith(".event.aggregation") }
                .map { it.routeId }
            val expectedRouteIds = catalogRoutes()
                .filter { it.routeId.endsWith(".event.count") }
                .map { it.routeId.removeSuffix("count") + "aggregation" }
            aggregationRouteIds.assert().containsExactlyInAnyOrder(*expectedRouteIds.toTypedArray())
        }

        @Test
        fun `snapshot schema routes should be aggregate scoped and independently identified`() {
            val get = requireNotNull(openAPI.paths["/cart/snapshot/schema"]?.get)
            val refresh = requireNotNull(openAPI.paths["/cart/snapshot/schema/refresh"]?.post)

            get.operationId.assert().isEqualTo("example.cart.snapshot_schema.get")
            refresh.operationId.assert().isEqualTo("example.cart.snapshot_schema.refresh")
            listOf(get, refresh).forEach { operation ->
                operation.responses.keys.assert().containsExactlyInAnyOrder("200", "400", "500", "503")
                operation.responses["200"]!!.content[Https.MediaType.APPLICATION_JSON]!!.schema.`$ref`
                    .assert().isEqualTo("#/components/schemas/wow.api.query.QueryModelSchemaMetadata")
            }
            openAPI.paths.keys.filter { it.endsWith("/cart/snapshot/schema") }.assert()
                .containsExactly("/cart/snapshot/schema")
            openAPI.paths.keys.filter { it.endsWith("/cart/snapshot/schema/refresh") }.assert()
                .containsExactly("/cart/snapshot/schema/refresh")
            openAPI.paths.keys.filter { it.contains("/snapshot/schema") }.none { path ->
                path.contains("{tenantId}") || path.contains("{ownerId}") || path.contains("{id}")
            }.assert().isTrue()
        }

        @Test
        fun `query schema value objects should use their string wire shape`() {
            listOf("QueryCapability", "QueryModel", "QueryValueType").forEach { typeName ->
                openAPI.components.schemas.getValue("wow.api.query.$typeName").types.assert()
                    .contains("string")
                    .doesNotContain("object")
            }
        }

        @Test
        fun `query schema enum values should accept any JSON value`() {
            val enumValues = openAPI.components.schemas
                .getValue("wow.api.query.QueryFieldSchemaMetadata")
                .properties.getValue("enumValues")
            val arraySchema = enumValues.anyOf.single { it.types?.contains("array") == true }
            val itemRef = requireNotNull(arraySchema.items.`$ref`)
            val itemSchema = openAPI.components.schemas.getValue(itemRef.substringAfterLast('/'))

            itemSchema.types.orEmpty().assert().isEmpty()
            itemSchema.properties.orEmpty().assert().isEmpty()
            itemSchema.allOf.orEmpty().assert().isEmpty()
            itemSchema.anyOf.orEmpty().assert().isEmpty()
            itemSchema.oneOf.orEmpty().assert().isEmpty()
        }

        @Test
        fun `temporal semantic schemas should match runtime JSON`() {
            val mapper = jsonMapper()
            val temporalTypes = listOf(
                Triple<QuerySemanticType, String, String>(
                    Temporal.Date,
                    "TEMPORAL_DATE",
                    "wow.api.query.Temporal.Date",
                ),
                Triple<QuerySemanticType, String, String>(
                    Temporal.Epoch(TimeUnit.SECONDS),
                    "TEMPORAL_EPOCH",
                    "wow.api.query.Temporal.Epoch",
                ),
                Triple<QuerySemanticType, String, String>(
                    Temporal.Formatted("yyyy-MM-dd"),
                    "TEMPORAL_FORMATTED",
                    "wow.api.query.Temporal.Formatted",
                ),
            )
            temporalTypes.forEach { (semanticType, expectedType, _) ->
                mapper.readTree(mapper.writeValueAsString(semanticType))["type"].stringValue()
                    .assert().isEqualTo(expectedType)
            }

            val baseRef = "#/components/schemas/wow.api.query.QuerySemanticType"
            val metadataSemanticType = openAPI.components.schemas
                .getValue("wow.api.query.QueryFieldSchemaMetadata")
                .properties.getValue("semanticType")
            metadataSemanticType.anyOf.assert().hasSize(2)
            metadataSemanticType.anyOf.mapNotNull { it.`$ref` }.assert().containsExactly(baseRef)
            metadataSemanticType.anyOf.single { it.types?.contains("null") == true }

            val baseSchema = openAPI.components.schemas.getValue("wow.api.query.QuerySemanticType")
            val expectedMapping = temporalTypes.associate { (_, type, component) ->
                type to "#/components/schemas/$component"
            }
            baseSchema.oneOf.map { it.`$ref` }.assert()
                .containsExactlyInAnyOrder(*expectedMapping.values.toTypedArray())
            baseSchema.anyOf.assert().isNull()
            baseSchema.discriminator.propertyName.assert().isEqualTo("type")
            baseSchema.discriminator.mapping.assert().isEqualTo(expectedMapping)

            temporalTypes.forEach { (_, expectedType, component) ->
                val schema = openAPI.components.schemas.getValue(component)
                schema.required.assert().contains("type")
                schema.properties.getValue("type").getConst().assert().isEqualTo(expectedType)
                schema.additionalProperties.assert().isNull()
            }
            openAPI.components.schemas.getValue("wow.api.query.Temporal.Date")
                .properties.keys.assert().containsExactly("type")
            val epochSchema = openAPI.components.schemas.getValue("wow.api.query.Temporal.Epoch")
            epochSchema.properties.keys.assert().containsExactlyInAnyOrder("type", "timeUnit")
            epochSchema.properties.getValue("timeUnit").`$ref`
                .assert().isEqualTo("#/components/schemas/example.TimeUnit")
            val formattedSchema = openAPI.components.schemas.getValue("wow.api.query.Temporal.Formatted")
            formattedSchema.properties.keys.assert().containsExactlyInAnyOrder("type", "pattern")
            formattedSchema.required.assert().containsExactlyInAnyOrder("type", "pattern")
        }

        @Test
        fun `should use aggregate query request bodies in snapshot routes`() {
            mapOf(
                "AggregationQuery" to "wow.api.query.AggregationQuery",
                "CountQuery" to "wow.api.query.FilterExpression",
                "SingleQuery" to "wow.api.query.SingleQuery",
                "ListQuery" to "wow.api.query.ListQuery",
                "PagedQuery" to "wow.api.query.PagedQuery",
            ).forEach { (queryType, schemaName) ->
                val requestBody = requireNotNull(openAPI.components.requestBodies["example.cart.$queryType"])
                requestBody.content[Https.MediaType.APPLICATION_JSON]?.schema?.`$ref`
                    .assert().isEqualTo("#/components/schemas/$schemaName")
            }
            listOf("aggregation", "count", "single", "list", "paged").forEach { operation ->
                requireNotNull(openAPI.paths["/cart/snapshot/$operation"]?.post?.requestBody?.`$ref`)
                    .assert().startsWith("#/components/requestBodies/example.cart.")
            }
            openAPI.components.schemas["wow.api.query.ListQuery"]
                ?.properties?.get("limit")?.minimum
                .assert().isEqualTo(BigDecimal.ZERO)
        }

        @Test
        fun `should keep generated query schemas closed to unknown properties`() {
            listOf("AggregationQuery", "SingleQuery", "ListQuery", "PagedQuery").forEach { queryType ->
                openAPI.components.schemas["wow.api.query.$queryType"]
                    ?.additionalProperties
                    .assert().isEqualTo(false)
            }
        }

        @Test
        fun `should generate cart routes without default tenant path`() {
            // Cart has @StaticTenantId → default appendTenantPath=false
            // MockVariableCommand overrides with appendTenantPath=ALWAYS, so exclude it
            val cartRoutes = catalogRoutes().filter {
                it.path.contains("/cart") && !it.routeId.contains("mock_variable_command")
            }
            cartRoutes.assert().isNotEmpty()
            cartRoutes.forEach {
                it.path.assert().doesNotContain("tenant")
            }
        }

        @Test
        fun `should generate order routes with spaced resource name`() {
            val orderRoutes = catalogRoutes().filter {
                it.path.contains("sales-order")
            }
            orderRoutes.assert().isNotEmpty()
        }

        @Test
        fun `should set correct tags for cart`() {
            val cartRoutes = catalogRoutes().filter {
                it.path.contains("/cart")
            }
            val tagNames = cartRoutes.flatMap { it.tags.map { tag -> tag.name } }.toSet()
            tagNames.assert().contains("customer")
        }

        @Test
        fun `should set aggregate tags in open api`() {
            openAPI.tags.assert().isNotEmpty()
        }
    }

    @Nested
    inner class CommandRoutes {

        @Test
        fun `should generate create order as POST with empty action`() {
            val route = findRoute("example.order.create_order")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.POST)
            route.path.assert().contains("sales-order")
            // Empty action → path ends at sales-order (no action suffix after resource name)
            route.path.assert().endsWith("/sales-order")
        }

        @Test
        fun `should generate change address as PUT`() {
            val route = findRoute("example.order.change_address")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.PUT)
            route.path.assert().contains("address")
        }

        @Test
        fun `should generate ship order as POST with package action`() {
            val route = findRoute("example.order.ship_order")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.POST)
            route.path.assert().contains("package")
        }

        @Test
        fun `should generate pay order as POST with pay action`() {
            val route = findRoute("example.order.pay_order")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.POST)
            route.path.assert().contains("pay")
        }

        @Test
        fun `should generate add cart item as POST`() {
            val route = findRoute("example.cart.add_cart_item")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.POST)
        }

        @Test
        fun `should generate view cart route`() {
            val route = findRoute("example.cart.view_cart")
            route.assert().isNotNull()
        }

        private fun findRoute(routeId: String) = routerSpecs.toRouteCatalog().routes.find {
            it.routeId == routeId
        }
    }

    private fun catalogRoutes() = routerSpecs.toRouteCatalog().routes

    @Nested
    inner class Schemas {

        @Test
        fun `should generate create order schema with fields`() {
            val schemas = openAPI.components.schemas
            schemas.assert().isNotEmpty()
            val createOrderSchema = schemas.entries.find {
                it.key.contains("CreateOrder")
            }
            createOrderSchema.assert().isNotNull()
            val properties = createOrderSchema!!.value.properties
            properties.assert().containsKey("items")
            properties.assert().containsKey("address")
            properties.assert().containsKey("fromCart")
        }

        @Test
        fun `should generate order created schema`() {
            val schemas = openAPI.components.schemas
            val orderCreatedSchema = schemas.entries.find {
                it.key.contains("OrderCreated")
            }
            orderCreatedSchema.assert().isNotNull()
        }

        @Test
        fun `should generate shipping address schema`() {
            val schemas = openAPI.components.schemas
            val addressSchema = schemas.entries.find {
                it.key.contains("ShippingAddress")
            }
            addressSchema.assert().isNotNull()
        }
    }

    @Nested
    inner class Components {

        @Test
        fun `should generate command header parameters`() {
            openAPI.components.parameters.assert().isNotEmpty()
        }

        @Test
        fun `should generate command responses`() {
            openAPI.components.responses.assert().isNotEmpty()
        }
    }
}
