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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.schema.query.JsonQuerySchemaSource
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class QueryComponentTest {

    @Test
    fun `aggregated fields should list getter fields once under their stored names`() {
        val stateSource = JsonQuerySchemaSource(typeResolver = { GetterState::class.java })
        val context = OpenAPIComponentContext.default()
        val fieldsRef = context.aggregatedFieldsSchema(aggregateMetadata<Cart, CartState>(), stateSource).`$ref`
        context.finish()
        val stateFields = context.schemas.getValue(
            fieldsRef.removePrefix(OpenAPIComponentContext.COMPONENTS_SCHEMAS_REF)
        )
            .enum.map { it.toString() }.filter { it.startsWith("state.") }

        stateFields.assert().containsExactly(
            "state.function",
            "state.function.contextName",
            "state.function.functionKind",
            "state.function.name",
            "state.function.processorName",
            "state.id",
            "state.isRetryable",
            "state.ref",
            "state.ref.id",
            "state.ref.version",
            "state.retries",
        )
        stateFields.assert().isEqualTo(storedPaths(GetterState.sample()).sorted())
    }

    private fun storedPaths(state: Any): List<String> {
        fun JsonNode.paths(prefix: String): List<String> = propertyNames().flatMap { name ->
            val path = "$prefix.$name"
            listOf(path) + get(name).paths(path)
        }
        return JsonSerializer.valueToTree<JsonNode>(state).paths("state")
    }
}

internal interface RetryableContract {
    val retries: Int
    val isRetryable: Boolean
        get() = retries < 3
}

internal data class VersionedRef(val id: String, override val version: Int) : Version

internal data class GetterState(
    val id: String,
    override val retries: Int,
    val ref: VersionedRef,
    val function: FunctionInfoData,
) : RetryableContract {
    companion object {
        fun sample(): GetterState = GetterState(
            id = "id",
            retries = 1,
            ref = VersionedRef("ref", 1),
            function = FunctionInfoData(FunctionKind.EVENT, "context", "processor", "name"),
        )
    }
}
