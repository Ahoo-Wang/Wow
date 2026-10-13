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
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

/** A schema's version is the SHA-256 of every fact it states: equal facts share it, any changed fact moves it. */
class QuerySchemaVersionTest {
    private val root = objectFixture(
        "name" to scalarFixture(),
        "tags" to arrayFixture(scalarFixture()),
        "labels" to mapFixture(scalarFixture()),
        "either" to QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(scalarFixture(), QueryValueSchema(QueryValueKind.NULL)),
        ),
    )
    private val schema = boundSchemaFixture(root)

    @Test
    fun `the version is a SHA-256 content hash shared by schemas stating the same facts`() {
        schema.version.assert().matches("[0-9a-f]{64}")
        schema.version.assert().isSameAs(schema.version)
        boundSchemaFixture(root).version.assert().isEqualTo(schema.version)
        queryModelSchemaVersion(schema).assert().isEqualTo(schema.version)
    }

    @Test
    fun `every model-wide fact moves the version`() {
        listOf(
            boundSchemaFixture(root, model = QueryModel("CUSTOM")),
            boundSchemaFixture(root, capabilities = setOf(QueryCapability.FULL_TEXT_TERMS)),
            with(schema) {
                QueryModelSchema(model, capabilities, definition, bindings, fullProjectionAvailable = false)
            },
            with(schema) {
                QueryModelSchema(model, capabilities, definition, bindings, approximateMetrics = setOf("PERCENTILE"))
            },
            with(schema) {
                QueryModelSchema(
                    model,
                    capabilities,
                    definition,
                    bindings,
                    storage = StorageSupport(arrayEquality = SupportMode.NONE),
                )
            },
            with(schema) { QueryModelSchema(model, capabilities, definition, bindings, provisional = true) },
            with(schema) { QueryModelSchema(model, capabilities, definition, bindings, profile = null) },
        ).forEach { changed -> changed.version.assert().isNotEqualTo(schema.version) }
    }

    @Test
    fun `every native binding fact moves the version`() {
        val path = QueryField("name").toPathTemplate()
        val physical = QueryField("other").toPathTemplate()
        fun rebound(bindings: QueryValueBindings) = with(schema) {
            QueryModelSchema(model, capabilities, definition, this.bindings + (path to bindings))
        }
        val current = schema.bindings.getValue(path)
        listOf(
            QueryValueBindings(emptyMap(), current.projectionPath, current.responsePath),
            QueryValueBindings(
                current.bindings + (QueryCapability.SORT to QueryFieldBindingTemplate(physical, null)),
                current.projectionPath,
                current.responsePath,
            ),
            QueryValueBindings(
                current.bindings + (
                    QueryCapability.SORT to QueryFieldBindingTemplate(
                        current.bindings.getValue(QueryCapability.SORT).physicalPath,
                        setOf(QueryStorageType("keyword")),
                    )
                    ),
                current.projectionPath,
                current.responsePath,
            ),
            QueryValueBindings(
                current.bindings + (
                    QueryCapability.SORT to QueryFieldBindingTemplate(
                        current.bindings.getValue(QueryCapability.SORT).physicalPath,
                        null,
                        QueryField("scope"),
                    )
                    ),
                current.projectionPath,
                current.responsePath,
            ),
            QueryValueBindings(current.bindings, physical, current.responsePath),
            QueryValueBindings(current.bindings, current.projectionPath, null),
        ).forEach { bindings -> rebound(bindings).version.assert().isNotEqualTo(schema.version) }
    }

    @Suppress("LongMethod")
    @Test
    fun `every logical fact moves the version`() {
        fun named(value: QueryValueSchema) = boundSchemaFixture(objectFixture("name" to value)).version
        val base = named(scalarFixture())
        val enumValue = JsonNodeFactory.instance.stringNode("A")
        listOf(
            QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                required = true
            ),
            QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER), nullable = false),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                semanticType = Temporal.Date,
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                enumValues = listOf(enumValue),
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                enumValues = listOf(enumValue),
                enumDescriptions = mapOf(enumValue to "A"),
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                title = "Name"
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                description = "Name",
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                variant = "v"
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                aliases = setOf(QueryField("alias")),
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                deprecated = QueryDeprecation(),
            ),
            arrayFixture(scalarFixture()),
            mapFixture(scalarFixture()),
            objectFixture("first" to scalarFixture()),
        ).forEach { changed -> named(changed).assert().isNotEqualTo(base) }
    }
}
