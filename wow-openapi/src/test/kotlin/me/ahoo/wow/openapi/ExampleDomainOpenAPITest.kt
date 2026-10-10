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

import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.disable.DisabledRouteAggregate
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contributor.DefaultRouteContributors
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.InferredQuerySchemaSource
import me.ahoo.wow.query.schema.querySchemaRegistration
import me.ahoo.wow.schema.query.JsonQueryModelSource
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
        routerSpecs.mergeOpenAPI(openAPI)
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
        fun `aggregate fields should follow the given query schema sources`() {
            val declared = BeanQuerySchemaSource(
                listOf(
                    querySchemaRegistration(Cart::class, QueryModel.SNAPSHOT) {
                        field("state.couponCode") { types(QueryValueType.STRING) }
                    }
                )
            )
            val declaredOpenAPI = OpenAPI()
            RouterSpecs(
                namedContext,
                OpenAPIComponentContext.default(defaultSchemaNamePrefix = namedContext.getContextAliasPrefix()),
                DefaultRouteContributors.all(),
                querySchemaSources = listOf(InferredQuerySchemaSource(JsonQueryModelSource()), declared),
            ).mergeOpenAPI(declaredOpenAPI)

            val fieldsKey = "example.cart.CartAggregatedFields"
            declaredOpenAPI.components.schemas.getValue(fieldsKey).`enum`.assert()
                .contains("state.couponCode", "state.items.productId")
            openAPI.components.schemas.getValue(fieldsKey).`enum`.assert().doesNotContain("state.couponCode")
        }

        @Test
        fun `aggregate fields should fall back to inference when the declarations do not merge`() {
            // Declared outside `state`, which the Catalog refuses as well.
            val conflicting = BeanQuerySchemaSource(
                listOf(
                    querySchemaRegistration(Cart::class, QueryModel.SNAPSHOT) {
                        field("outside") { types(QueryValueType.STRING) }
                    }
                )
            )
            val fallbackOpenAPI = OpenAPI()
            RouterSpecs(
                namedContext,
                OpenAPIComponentContext.default(defaultSchemaNamePrefix = namedContext.getContextAliasPrefix()),
                DefaultRouteContributors.all(),
                querySchemaSources = listOf(InferredQuerySchemaSource(JsonQueryModelSource()), conflicting),
            ).mergeOpenAPI(fallbackOpenAPI)

            val fieldsKey = "example.cart.CartAggregatedFields"
            fallbackOpenAPI.components.schemas.getValue(fieldsKey).`enum`
                .assert().isEqualTo(openAPI.components.schemas.getValue(fieldsKey).`enum`)
        }

        @Test
        fun `projection and sort should reference the query field component`() {
            val queryFieldRef = "#/components/schemas/wow.api.query.QueryField"
            val schemas = openAPI.components.schemas

            schemas.assert().containsKey("wow.api.query.QueryField")
            schemas.getValue("wow.api.query.QueryField").types.assert().containsExactly("string")

            val projection = schemas.getValue("wow.api.query.Projection")
            listOf("include", "exclude").forEach { property ->
                projection.properties.getValue(property).items.`$ref`.assert().isEqualTo(queryFieldRef)
            }
            schemas.getValue("wow.api.query.Sort").properties.getValue("field").`$ref`.assert()
                .isEqualTo(queryFieldRef)
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
            val logicalFieldRef = "#/components/schemas/wow.api.query.QueryField"
            openAPI.components.schemas.getValue("wow.api.query.QueryField").types
                .assert().contains("string").doesNotContain("object")
            querySchema.properties.getValue("filter").`$ref`
                .assert().isEqualTo("#/components/schemas/wow.api.query.FilterExpression")
            assertFilterExpressionQueryFieldDefinition()
            val elementSchema = openAPI.components.schemas.getValue("wow.api.query.AggregationElement")
            assertAggregationExpressionSchema()
            listOf(
                elementSchema.properties.getValue("path"),
                openAPI.components.schemas.getValue("wow.api.query.AggregationExpression.Field")
                    .properties.getValue("field"),
                openAPI.components.schemas.getValue("wow.api.query.AggregationGroup.DateHistogram")
                    .properties.getValue("field"),
            ).forEach { fieldSchema ->
                fieldSchema.`$ref`.assert().isEqualTo(logicalFieldRef)
            }
            // TERMS and HISTOGRAM read a field or, instead, a computed expression.
            listOf("Histogram", "Terms").forEach { group ->
                val properties = openAPI.components.schemas.getValue("wow.api.query.AggregationGroup.$group").properties
                properties.getValue("field").anyOf.map { it.`$ref` }.assert().contains(logicalFieldRef)
                properties.getValue("expression").anyOf.map { it.`$ref` }.assert()
                    .contains("#/components/schemas/wow.api.query.AggregationExpression")
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
                "#/components/schemas/wow.api.query.AggregationMetric.DistinctCount",
                "#/components/schemas/wow.api.query.AggregationMetric.Percentile",
                "#/components/schemas/wow.api.query.AggregationMetric.Derived",
                "#/components/schemas/wow.api.query.AggregationMetric.First",
                "#/components/schemas/wow.api.query.AggregationMetric.Last",
            )
            val firstSchema = openAPI.components.schemas.getValue("wow.api.query.AggregationMetric.First")
            firstSchema.required.assert().containsExactlyInAnyOrder("field", "alias", "type")
            firstSchema.properties.getValue("orderBy").anyOf.map { it.`$ref` }.assert().contains(logicalFieldRef)
            metricSchema.discriminator.propertyName.assert().isEqualTo("type")
            (metricSchema.properties.getValue("alias").readOnly == true).assert().isFalse()
            assertMetricFilterSchema(metricSchema)
            val anySchema = openAPI.components.schemas.getValue("wow.api.query.AggregationMetric.Any")
            anySchema.required.assert().containsExactlyInAnyOrder("field", "alias", "type")
            anySchema.properties.getValue("field").`$ref`.assert().isEqualTo(logicalFieldRef)
        }

        private fun assertMetricFilterSchema(metricSchema: Schema<*>) {
            val filterRef = "#/components/schemas/wow.api.query.FilterExpression"
            metricSchema.properties.getValue("filter").`$ref`.assert().isEqualTo(filterRef)
            metricSchema.oneOf.mapNotNull { it.`$ref` }.forEach { metricRef ->
                val concreteSchema = openAPI.components.schemas.getValue(metricRef.substringAfterLast('/'))
                concreteSchema.properties.getValue("filter").`$ref`.assert().isEqualTo(filterRef)
                concreteSchema.required.assert().doesNotContain("filter")
            }
        }

        private fun assertFilterExpressionQueryFieldDefinition() {
            val definitions = ObjectMapperFactory.createJson31()
                .valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                    openAPI.components.schemas.getValue("wow.api.query.FilterExpression"),
                )["definitions"]
            definitions.has("queryField").assert().isTrue()
            definitions.has("logicalField").assert().isFalse()
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
                "#/components/schemas/wow.api.query.AggregationExpression.DateDiff",
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
        fun `query schema routes should be model scoped and independently identified`() {
            listOf("snapshot", "event").forEach { model ->
                val get = requireNotNull(openAPI.paths["/cart/$model/schema"]?.get)
                get.operationId.assert().isEqualTo("example.cart.${model}_schema.get")
                get.responses.keys.assert().containsExactlyInAnyOrder("200", "304", "400", "500", "503")
                get.responses["200"]!!.content[Https.MediaType.APPLICATION_JSON]!!.schema.`$ref`
                    .assert().isEqualTo("#/components/schemas/wow.api.query.QueryModelDescriptor")
                openAPI.paths.keys.filter { it.endsWith("/cart/$model/schema") }.assert()
                    .containsExactly("/cart/$model/schema")
                // The 9.1 refresh route stays as a deprecated alias of the wowQuerySchema actuator's revalidation.
                val refresh = requireNotNull(openAPI.paths["/cart/$model/schema/refresh"]?.post)
                refresh.operationId.assert().isEqualTo("example.cart.${model}_schema.refresh")
                refresh.responses["200"]!!.content[Https.MediaType.APPLICATION_JSON]!!.schema.`$ref`
                    .assert().isEqualTo("#/components/schemas/wow.api.query.QueryModelDescriptor")
                openAPI.paths.keys.filter { it.contains("/$model/schema") }.none { path ->
                    path.contains("{tenantId}") || path.contains("{ownerId}") || path.contains("{id}")
                }.assert().isTrue()
            }
        }

        @Test
        fun `query schema value objects should use their string wire shape`() {
            listOf("QueryModel", "QueryValueType").forEach { typeName ->
                openAPI.components.schemas.getValue("wow.api.query.$typeName").types.assert()
                    .contains("string")
                    .doesNotContain("object")
            }
        }

        @Test
        fun `query schema descriptor should expose a flat field index without physical facts`() {
            val descriptor = openAPI.components.schemas.getValue("wow.api.query.QueryModelDescriptor")
            descriptor.properties.keys.assert().contains(
                "model",
                "version",
                "record",
                "limits",
                "analysis",
                "fields",
                "elements",
                "dynamic",
                "constraints",
            )
            descriptor.properties.getValue("fields").items.`$ref`.assert()
                .isEqualTo("#/components/schemas/wow.api.query.FieldDescriptor")
            openAPI.components.schemas.getValue("wow.api.query.FieldDescriptor").properties.keys.assert()
                .contains("path", "filter", "sort", "aggregate", "scope")
                .doesNotContain("physicalPath", "storageTypes", "bindings", "capabilities")
        }

        @Test
        fun `query schema enum values should accept any JSON value`() {
            val itemRef = requireNotNull(
                openAPI.components.schemas.getValue(
                    "wow.api.query.EnumValueDescriptor"
                ).properties.getValue("value").`$ref`,
            )
            val itemSchema = openAPI.components.schemas.getValue(itemRef.substringAfterLast('/'))

            itemSchema.types.orEmpty().assert().isEmpty()
            itemSchema.properties.orEmpty().assert().isEmpty()
            itemSchema.allOf.orEmpty().assert().isEmpty()
            itemSchema.anyOf.orEmpty().assert().isEmpty()
            itemSchema.oneOf.orEmpty().assert().isEmpty()
        }

        @Test
        @Suppress("LongMethod")
        fun `semantic type schemas should match runtime JSON`() {
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
                Triple<QuerySemanticType, String, String>(
                    me.ahoo.wow.api.query.schema.NumericFormat.Decimal(2),
                    "DECIMAL",
                    "wow.api.query.NumericFormat.Decimal",
                ),
                Triple<QuerySemanticType, String, String>(
                    me.ahoo.wow.api.query.schema.NumericFormat.Money(currency = "CNY", scale = 2),
                    "MONEY",
                    "wow.api.query.NumericFormat.Money",
                ),
                Triple<QuerySemanticType, String, String>(
                    me.ahoo.wow.api.query.schema.TimeSpan(TimeUnit.SECONDS),
                    "DURATION",
                    "wow.api.query.TimeSpan",
                ),
                Triple<QuerySemanticType, String, String>(
                    me.ahoo.wow.api.query.schema.Reference(contextName = "example", aggregateName = "order"),
                    "REFERENCE",
                    "wow.api.query.Reference",
                ),
            )
            temporalTypes.forEach { (semanticType, expectedType, _) ->
                mapper.readTree(mapper.writeValueAsString(semanticType))["type"].stringValue()
                    .assert().isEqualTo(expectedType)
            }

            val baseRef = "#/components/schemas/wow.api.query.QuerySemanticType"
            val metadataSemanticType = openAPI.components.schemas
                .getValue("wow.api.query.FieldDescriptor")
                .properties.getValue("semantic")
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
            val spanSchema = openAPI.components.schemas.getValue("wow.api.query.TimeSpan")
            spanSchema.properties.keys.assert().containsExactlyInAnyOrder("type", "timeUnit")
            spanSchema.required.assert().containsExactlyInAnyOrder("type", "timeUnit")
            val referenceSchema = openAPI.components.schemas.getValue("wow.api.query.Reference")
            referenceSchema.properties.keys.assert().containsExactlyInAnyOrder(
                "type",
                "contextName",
                "aggregateName",
                "contextNameField",
                "aggregateNameField",
            )
            referenceSchema.required.assert().containsExactly("type")
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
        fun `should publish tenant and owner query variants for an owned aggregate without a static tenant`() {
            val tenantOwnerRoutes = catalogRoutes().filter { it.routeId.startsWith("example.order.tenant.owner.") }
            tenantOwnerRoutes.associate { it.routeId to it.path }.assert().isEqualTo(
                mapOf(
                    "example.order.tenant.owner.snapshot.count" to "snapshot/count",
                    "example.order.tenant.owner.snapshot.aggregation" to "snapshot/aggregation",
                    "example.order.tenant.owner.snapshot.list_query" to "snapshot/list",
                    "example.order.tenant.owner.snapshot_state.list_query" to "snapshot/list/state",
                    "example.order.tenant.owner.snapshot.paged_query" to "snapshot/paged",
                    "example.order.tenant.owner.snapshot_state.paged_query" to "snapshot/paged/state",
                    "example.order.tenant.owner.snapshot.cursor_query" to "snapshot/cursor",
                    "example.order.tenant.owner.snapshot_state.cursor_query" to "snapshot/cursor/state",
                    "example.order.tenant.owner.snapshot.single" to "snapshot/single",
                    "example.order.tenant.owner.snapshot_state.single" to "snapshot/single/state",
                    // The snapshot load already carried both segments; it is not a query variant.
                    "example.order.tenant.owner.snapshot.load" to "{id}/snapshot",
                    "example.order.tenant.owner.event.aggregation" to "event/aggregation",
                    "example.order.tenant.owner.event.count" to "event/count",
                    "example.order.tenant.owner.event.list_query" to "event/list",
                    "example.order.tenant.owner.event.paged_query" to "event/paged",
                    "example.order.tenant.owner.event.cursor_query" to "event/cursor",
                ).mapValues { (_, suffix) -> "/tenant/{tenantId}/owner/{ownerId}/sales-order/$suffix" }
            )
            tenantOwnerRoutes.filter { it.routeId != "example.order.tenant.owner.snapshot.load" }.forEach { route ->
                route.method.assert().isEqualTo(Https.Method.POST)
                route.parameters.map { it.name }.assert().containsExactly("tenantId", "ownerId", "Wow-Space-Id")
                route.summary.assert().endsWith(" Within Tenant Owner")
                val withinOwner = catalogRoutes().single {
                    it.routeId == route.routeId.replace(".tenant.owner.", ".owner.")
                }
                route.handlerKey.assert().isEqualTo(withinOwner.handlerKey)
                route.summary.assert().isEqualTo(withinOwner.summary.replace(" Within Owner", " Within Tenant Owner"))
                val operation = requireNotNull(openAPI.paths[route.path]?.post)
                operation.operationId.assert().isEqualTo(route.routeId)
                operation.summary.assert().isEqualTo(route.summary)
            }
        }

        @Test
        fun `should not publish tenant and owner query variants for an aggregate with a static tenant`() {
            catalogRoutes().filter { it.path.contains("/cart/snapshot") || it.path.contains("/cart/event") }
                .forEach { it.path.assert().doesNotStartWith("/tenant/") }
        }

        @Test
        fun `should keep operation ids unique`() {
            val operationIds = openAPI.paths.values.flatMap { it.readOperations() }.map { it.operationId }
            operationIds.assert().doesNotHaveDuplicates()
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
