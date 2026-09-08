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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import kotlin.reflect.jvm.javaField

class QuerySchemaMetadataTest {
    @Test
    fun `metadata preserves every logical shape and sorted property names`() {
        val scalar = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
        val definition = LogicalQuerySchema(
            QueryValueSchema(
                QueryValueKind.OBJECT,
                properties = linkedMapOf(
                    "unknown" to QueryValueSchema(QueryValueKind.UNKNOWN),
                    "homes" to QueryValueSchema(
                        QueryValueKind.OBJECT,
                        additionalProperties = QueryValueSchema(
                            QueryValueKind.ARRAY, items = QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf("city" to scalar)),
                        )
                    ),
                    "choice" to QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(scalar, QueryValueSchema(QueryValueKind.NULL))),
                )
            )
        )
        val schema = bind(definition)
        val metadata = schema.toMetadata()
        metadata.root.properties.keys.assert().containsExactly("choice", "homes", "unknown")
        metadata.root.properties.getValue("unknown").kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
        metadata.root.properties.getValue("choice").alternatives.map { it.kind }.assert()
            .containsExactly(QueryValueKind.SCALAR, QueryValueKind.NULL)
        metadata.root.properties.getValue("homes").additionalProperties!!.let { array ->
            array.kind.assert().isEqualTo(QueryValueKind.ARRAY)
            array.valueTypes.assert().isEmpty()
            array.items!!.properties.getValue("city").valueTypes.assert().containsExactly(QueryValueType.STRING)
            array.items!!.properties.getValue("city").capabilities.assert().doesNotContain(QueryCapability.CURSOR_SORT)
        }
        jsonMapper().writeValueAsString(
            metadata
        ).assert().doesNotContain("\"native", "storageTypes", "projectionPath", "bindings", "dynamicChildren")
    }

    @Test
    fun `metadata uses shared protection for symbolic map values and native aliases without trimming raw facts`() {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        val rule = MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
        val masked = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), maskRule = rule)
        val ordinary = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
        val definition =
            LogicalQuerySchema(
                objectFixture(
                    "state" to QueryValueSchema(
                        QueryValueKind.OBJECT,
                        properties = mapOf(
                            "secret" to masked,
                            "alias" to ordinary,
                            "map" to QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = masked),
                        )
                    )
                )
            )
        val schema = bind(definition, shared = setOf("secret", "alias"))
        val metadata = schema.toMetadata()
        listOf(
            metadata.root.properties.getValue("state").properties.getValue("secret"),
            metadata.root.properties.getValue("state").properties.getValue("alias"),
            metadata.root.properties.getValue("state").properties.getValue("map").additionalProperties!!
        ).forEach { value ->
            value.capabilities.assert().contains(QueryCapability.EXACT_MATCH)
                .doesNotContain(QueryCapability.CURSOR_SORT, QueryCapability.AGGREGATE_TERMS)
        }
        metadata.root.properties.getValue("state").properties.getValue("secret").masked.assert().isTrue()
        schema.bindings.values.filter { it.bindings.isNotEmpty() }.forEach {
            it.bindings.keys.assert().contains(QueryCapability.CURSOR_SORT, QueryCapability.AGGREGATE_TERMS)
        }
        jsonMapper().writeValueAsString(metadata).assert().doesNotContain("FullMaskStrategy", "maskRule", "annotation")
    }

    private fun bind(definition: LogicalQuerySchema, shared: Set<String> = emptySet()): QueryModelSchema = QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        definition,
        definition.values.keys.filter { it.segments.isNotEmpty() }.associateWith { path ->
            val physical = if ((path.segments.last() as? QueryPathSegment.Property)?.name in shared) {
                QueryPathTemplate(listOf(QueryPathSegment.Property("native_shared")))
            } else {
                QueryPathTemplate(listOf(QueryPathSegment.Property("native")) + path.segments)
            }
            QueryValueBindings(
                setOf(QueryCapability.EXACT_MATCH, QueryCapability.CURSOR_SORT, QueryCapability.AGGREGATE_TERMS)
                    .associateWith { QueryFieldBindingTemplate(physical, setOf(QueryStorageType("keyword"))) },
                projectionPath = physical,
                responsePath = path,
            )
        },
    )

    private data class Masked(@field:Mask val secret: String)
}
