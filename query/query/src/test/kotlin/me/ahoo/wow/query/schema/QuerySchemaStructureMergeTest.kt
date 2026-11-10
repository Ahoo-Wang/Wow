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
import me.ahoo.wow.api.query.QueryValueKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** How a value's structure merges: array and map nodes, items, structure replacement, properties, unions, an opened opaque value. */
internal class QuerySchemaStructureMergeTest : QuerySchemaMergerFixtures() {
    @Test
    fun `array and map nodes retain independent value nullability`() {
        val schema = mergeTrees(
            QueryFieldDeclaration(
                additionalProperties = DeclarationValue.Set(
                    QueryFieldDeclaration(
                        nullable = DeclarationValue.Set(true),
                        items = DeclarationValue.Set(
                            QueryFieldDeclaration(
                                nullable = DeclarationValue.Set(false),
                                properties = DeclarationValue.Set(mapOf("secret" to string())),
                            )
                        ),
                    )
                ),
                nullable = DeclarationValue.Set(false),
            )
        )
        val map = schema.root.properties.getValue("state").properties.getValue("values")
        map.kind.assert().isEqualTo(QueryValueKind.OBJECT)
        map.nullable.assert().isFalse()
        val array = checkNotNull(map.additionalProperties)
        array.kind.assert().isEqualTo(QueryValueKind.ARRAY)
        array.nullable.assert().isTrue()
        val item = checkNotNull(array.items)
        item.nullable.assert().isFalse()
        item.properties.getValue("secret").valueTypes.assert().containsExactly(QueryValueType.STRING)
    }

    @Test
    fun `items and map values are never required, whatever they declare`() {
        val schema = mergeTrees(
            QueryFieldDeclaration(
                additionalProperties = DeclarationValue.Set(
                    QueryFieldDeclaration(
                        required = DeclarationValue.Set(true),
                        items = DeclarationValue.Set(string().copy(required = DeclarationValue.Set(true))),
                    ),
                ),
            ),
        )
        val array = schema.root.properties.getValue("state").properties.getValue("values").additionalProperties!!
        array.required.assert().isFalse()
        array.items!!.required.assert().isFalse()
    }

    @Test
    fun `structure replacement replaces the whole value of a map`() {
        val lower = QueryFieldDeclaration(
            additionalProperties = DeclarationValue.Set(
                QueryFieldDeclaration(
                    properties = DeclarationValue.Set(
                        mapOf("secret" to string(), "old" to QueryFieldDeclaration())
                    ),
                )
            )
        )
        val higher = QueryFieldDeclaration(
            additionalProperties = DeclarationValue.Set(
                QueryFieldDeclaration(
                    properties = DeclarationValue.Set(
                        mapOf(
                            "secret" to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
                        )
                    ),
                )
            )
        )
        val value = mergeTrees(
            lower,
            higher
        ).root.properties.getValue("state").properties.getValue("values").additionalProperties!!
        value.properties.keys.assert().isEqualTo(setOf("secret"))
        value.properties.getValue("secret").valueTypes.assert().containsExactly(QueryValueType.STRING)
    }

    @Test
    fun `named properties merge by name across priorities`() {
        val lower = QueryFieldDeclaration(
            properties = DeclarationValue.Set(mapOf("a" to string(), "b" to QueryFieldDeclaration())),
        )
        val higher = QueryFieldDeclaration(
            properties = DeclarationValue.Set(mapOf("a" to QueryFieldDeclaration(title = DeclarationValue.Set("A")))),
        )
        val values = mergeTrees(lower, higher).root.properties.getValue("state").properties.getValue("values")
        values.properties.keys.assert().containsExactly("a", "b")
        values.properties.getValue("a").title.assert().isEqualTo("A")
        values.properties.getValue("a").valueTypes.assert().containsExactly(QueryValueType.STRING)
    }

    @Test
    fun `union alternatives preserve distinct shapes`() {
        val array =
            QueryFieldDeclaration(
                items = DeclarationValue.Set(
                    QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
                )
            )
        val lower = QueryFieldDeclaration(alternatives = DeclarationValue.Set(listOf(string(), array)))
        val schema = mergeTrees(lower)
        val union = schema.root.properties.getValue("state").properties.getValue("values")
        union.kind.assert().isEqualTo(QueryValueKind.UNION)
        union.cardinality.assert().isNull()
        union.alternatives.map { it.kind }.assert().isEqualTo(listOf(QueryValueKind.SCALAR, QueryValueKind.ARRAY))
    }

    @Test
    fun `a non-null union rejects nullable alternatives and an invalid structure is a conflict`() {
        assertThrows<QuerySchemaConflictException> {
            mergeTrees(
                QueryFieldDeclaration(
                    nullable = DeclarationValue.Set(false),
                    alternatives = DeclarationValue.Set(
                        listOf(string(), QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.NULL)))
                    ),
                ),
            )
        }
        // An array cannot have properties.
        assertThrows<QuerySchemaConflictException> {
            mergeTrees(
                QueryFieldDeclaration(
                    kind = DeclarationValue.Set(QueryValueKind.ARRAY),
                    items = DeclarationValue.Set(string()),
                    properties = DeclarationValue.Set(mapOf("a" to string())),
                ),
            )
        }.cause.assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun manifestAndDeclared(manifest: QueryFieldDeclaration, declared: QuerySchemaDeclaration) = merger.merge(
        system(),
        listOf(
            PrioritizedQuerySchemaDeclaration(
                QuerySchemaSourcePriority.MANIFEST,
                QuerySchemaDeclaration(mapOf(QueryField("state.config") to manifest)),
            ),
            PrioritizedQuerySchemaDeclaration(QuerySchemaSourcePriority.BEAN, declared),
        ),
    )

    @Test
    fun `a declaration of kind OBJECT opens an opaque value as an object`() {
        val declared = QuerySchemaDeclarationBuilder().apply {
            field("state.config") {
                kind(QueryValueKind.OBJECT)
                property("kind") { types(QueryValueType.STRING) }
            }
        }.build()
        val config = manifestAndDeclared(
            opaque,
            declared
        ).root.properties.getValue("state").properties.getValue("config")
        config.kind.assert().isEqualTo(QueryValueKind.OBJECT)
        config.valueTypes.assert().isEqualTo(setOf(QueryValueType.OBJECT))
        config.properties.getValue("kind").valueTypes.assert().isEqualTo(setOf(QueryValueType.STRING))
    }

    @Test
    fun `opening an opaque value compiles as stating its object value type did`() {
        val declared = QuerySchemaDeclarationBuilder().apply {
            field("state.config") {
                kind(QueryValueKind.OBJECT)
                property("kind") { types(QueryValueType.STRING) }
            }
        }.build()
        // How a declaration had to open one before 9.3.0: edit the value types of the built declaration by hand.
        val handEdited = QuerySchemaDeclaration(
            declared.fields.mapValues { (_, field) ->
                field.copy(valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            }
        )
        val opened = manifestAndDeclared(opaque, declared)
        val edited = manifestAndDeclared(opaque, handEdited)
        opened.values.keys.assert().isEqualTo(edited.values.keys)
        opened.values.forEach { (path, value) ->
            val reference = edited.values.getValue(path)
            value.kind.assert().isEqualTo(reference.kind)
            value.valueTypes.assert().isEqualTo(reference.valueTypes)
            value.nullable.assert().isEqualTo(reference.nullable)
            value.properties.keys.assert().isEqualTo(reference.properties.keys)
        }
    }

    @Test
    fun `a declaration of kind OBJECT still cannot turn a scalar into an object`() {
        val declared = QuerySchemaDeclarationBuilder().apply {
            field("state.config") {
                kind(QueryValueKind.OBJECT)
                property("kind") { types(QueryValueType.STRING) }
            }
        }.build()
        val scalar = QueryFieldDeclaration(
            kind = DeclarationValue.Set(QueryValueKind.SCALAR),
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        )
        assertThrows<QuerySchemaConflictException> { manifestAndDeclared(scalar, declared) }
    }

    private fun mergeTrees(vararg declarations: QueryFieldDeclaration): LogicalQuerySchema = merger.merge(
        system(),
        declarations.mapIndexed { index, declaration ->
            PrioritizedQuerySchemaDeclaration(
                index,
                QuerySchemaDeclaration(mapOf(QueryField("state.values") to declaration))
            )
        }
    )
}
