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

package me.ahoo.wow.openapi.aggregate.state

import io.swagger.v3.oas.annotations.enums.ParameterIn
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Wow
import me.ahoo.wow.openapi.BatchComponent.Parameter.batchLimitPathParameter
import me.ahoo.wow.openapi.BatchComponent.Parameter.headVersionPathParameter
import me.ahoo.wow.openapi.BatchComponent.Parameter.tailVersionPathParameter
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test

internal class AggregateTracingRouteSpecTest {

    private val inlineContext = OpenAPIComponentContext.default(inline = true)
    private val aggregateRouteMetadata = aggregateRouteMetadata<MockCommandAggregate>()
    private val namedContext = MaterializedNamedBoundedContext("test-service")

    @Test
    fun `should have get method`() {
        val spec = AggregateTracingRouteSpec(namedContext, aggregateRouteMetadata, inlineContext)

        spec.method.assert().isEqualTo(Https.Method.GET)
    }

    @Test
    fun `should keep request body and responses unchanged`() {
        val spec = AggregateTracingRouteSpec(namedContext, aggregateRouteMetadata, inlineContext)

        spec.requestBody.assert().isNull()
        spec.responses[Https.Code.OK].assert().isNotNull()
    }

    @Test
    fun `should expose optional tracing window query parameters`() {
        val spec = AggregateTracingRouteSpec(namedContext, aggregateRouteMetadata, inlineContext)
        val parametersByName = spec.parameters.associateBy { it.name }

        listOf("headVersion", "tailVersion", "limit").forEach { parameterName ->
            val parameter = parametersByName[parameterName]
            parameter.assert().isNotNull()
            parameter!!.`in`.assert().isEqualTo(ParameterIn.QUERY.toString())
            (parameter.required == true).assert().isFalse()
        }
    }

    @Test
    fun `should not add tracing window parameters to path`() {
        val spec = AggregateTracingRouteSpec(namedContext, aggregateRouteMetadata, inlineContext)

        listOf("headVersion", "tailVersion", "limit").forEach { parameterName ->
            spec.path.contains("{$parameterName}").assert().isFalse()
        }
    }

    @Test
    fun `should register tracing query parameters with unique component keys`() {
        val defaultContext = OpenAPIComponentContext.default()
        defaultContext.headVersionPathParameter()
        defaultContext.tailVersionPathParameter()
        defaultContext.batchLimitPathParameter()

        val spec = AggregateTracingRouteSpec(namedContext, aggregateRouteMetadata, defaultContext)
        val expectedKeysByName = mapOf(
            "headVersion" to "wow.aggregate-tracing.headVersion",
            "tailVersion" to "wow.aggregate-tracing.tailVersion",
            "limit" to "wow.aggregate-tracing.limit",
        )

        val parameterRefs = spec.parameters.mapNotNull { it.`$ref` }.toSet()
        expectedKeysByName.forEach { (parameterName, componentKey) ->
            parameterRefs.assert().contains("${OpenAPIComponentContext.COMPONENTS_PARAMETERS_REF}$componentKey")
            val parameter = defaultContext.parameters[componentKey]
            parameter.assert().isNotNull()
            parameter!!.name.assert().isEqualTo(parameterName)
            parameter.`in`.assert().isEqualTo(ParameterIn.QUERY.toString())
            (parameter.required == true).assert().isFalse()
        }

        val batchKeysByName = mapOf(
            "headVersion" to "${Wow.WOW_PREFIX}headVersion",
            "tailVersion" to "${Wow.WOW_PREFIX}tailVersion",
            "limit" to "${Wow.WOW_PREFIX}limit",
        )
        expectedKeysByName.values.forEach { componentKey ->
            batchKeysByName.values.contains(componentKey).assert().isFalse()
        }
        batchKeysByName.forEach { (parameterName, componentKey) ->
            val parameter = defaultContext.parameters[componentKey]
            parameter.assert().isNotNull()
            parameter!!.name.assert().isEqualTo(parameterName)
            parameter.`in`.assert().isEqualTo(ParameterIn.PATH.toString())
        }
    }
}
