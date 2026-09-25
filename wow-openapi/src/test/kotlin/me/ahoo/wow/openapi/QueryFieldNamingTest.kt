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

import io.swagger.v3.oas.models.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.InferredQuerySchemaSource
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.schema.query.JsonQueryModelSource
import org.junit.jupiter.api.Test

/**
 * Field names have one source, Jackson's serialized names: the state schema OpenAPI publishes for response bodies and
 * the fields query inference admits must name the same paths, or a client that reads one cannot query the other.
 */
class QueryFieldNamingTest {
    @Test
    fun `openapi state schemas and query inference name the same fields`() {
        listOf(CartState::class.java, OrderState::class.java).forEach { stateType ->
            openApiPaths(stateType).assert().isNotEmpty().isEqualTo(inferredPaths(stateType))
        }
        openApiPaths(CartState::class.java).assert().contains("state.items.productId")
    }

    private fun openApiPaths(stateType: Class<*>): Set<String> {
        val context = OpenAPIComponentContext.default(inline = false)
        val root = context.schema(stateType)
        context.finish()
        fun Schema<*>.resolved(): Schema<*> =
            `$ref`?.let { context.schemas.getValue(it.removePrefix(OpenAPIComponentContext.COMPONENTS_SCHEMAS_REF)).resolved() }
                ?: this
        return buildSet {
            fun visit(schema: Schema<*>, path: String, depth: Int) {
                if (depth > MAX_DEPTH) return
                val value = schema.resolved()
                value.properties.orEmpty().forEach { (name, child) ->
                    if (child.resolved().writeOnly == true) return@forEach
                    val childPath = "$path.$name"
                    add(childPath)
                    visit(child, childPath, depth + 1)
                }
                value.items?.let { visit(it, path, depth + 1) }
                listOfNotNull(value.allOf, value.anyOf, value.oneOf).flatten().forEach { visit(it, path, depth + 1) }
            }
            visit(root, "state", 0)
        }
    }

    private fun inferredPaths(stateType: Class<*>): Set<String> {
        val context = QuerySchemaContext(MaterializedNamedAggregate("test", "test"), QueryModel.SNAPSHOT)
        val declaration = InferredQuerySchemaSource(JsonQueryModelSource(), typeResolver = { stateType })
            .load(context).single().block()!!
        return buildSet {
            fun visit(value: QueryFieldDeclaration, path: String) {
                (value.properties as? DeclarationValue.Set)?.value.orEmpty().forEach { (name, child) ->
                    val childPath = "$path.$name"
                    add(childPath)
                    visit(child, childPath)
                }
                (value.items as? DeclarationValue.Set)?.value?.let { visit(it, path) }
                (value.alternatives as? DeclarationValue.Set)?.value.orEmpty().forEach { visit(it, path) }
            }
            declaration.fields.forEach { (field, value) -> visit(value, field.path) }
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
    }
}
