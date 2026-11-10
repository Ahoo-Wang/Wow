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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.ManifestFixtures.ORDER
import me.ahoo.wow.query.schema.ManifestFixtures.schemaOf
import me.ahoo.wow.query.schema.ManifestFixtures.stateManifest
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A field marked as prose searched by words (`@QueryText`, `x-wow-text`): where the marker lands, and what refuses it. */
class TextValueSchemaTest {
    private val text = mapOf(ManifestQueryKeywords.TEXT to emptyMap<String, Any?>())
    private val string = mapOf("type" to "string")

    private fun QueryModelSchema.value(path: String): QueryValueSchema = checkNotNull(field(QueryField(path))).value

    private suspend fun manifestSchema(properties: Map<String, Any?>): QueryModelSchema =
        schemaOf(ManifestQuerySchemaSource(stateManifest(properties)))

    private suspend fun codeSchema(block: QuerySchemaDeclarationBuilder.() -> Unit): QueryModelSchema = schemaOf(
        BeanQuerySchemaSource(listOf(querySchemaRegistration(ORDER, QueryModel.SNAPSHOT, block))),
    )

    @Test
    fun `x-wow-text marks each string leaf, an array's items and a union's strings, and nothing else`() = runTest {
        val schema = manifestSchema(
            mapOf(
                "name" to string + text,
                "note" to mapOf("type" to listOf("string", "null")) + text,
                "tags" to mapOf("type" to "array", "items" to string) + text,
                "either" to mapOf("anyOf" to listOf(string, mapOf("type" to "null"))) + text,
                "oneOrMany" to mapOf(
                    "anyOf" to listOf(string, mapOf("type" to "array", "items" to string), mapOf("type" to "null")),
                ) + text,
                "code" to string,
            ),
        )
        schema.value("state.name").text.assert().isTrue()
        schema.value("state.note").text.assert().isTrue()
        schema.value("state.tags").let { tags ->
            tags.text.assert().isFalse()
            checkNotNull(tags.items).text.assert().isTrue()
        }
        schema.value("state.either").operationValues().filter { it.kind != QueryValueKind.NULL }
            .map { it.text }.assert().containsExactly(true)
        schema.value("state.oneOrMany").let { union ->
            union.kind.assert().isEqualTo(QueryValueKind.UNION)
            val (one, many) = union.alternatives.filter { it.kind != QueryValueKind.NULL }
            one.text.assert().isTrue()
            checkNotNull(many.items).text.assert().isTrue()
        }
        schema.value("state.code").text.assert().isFalse()
        schema.value("tenantId").text.assert().isFalse()
    }

    @Test
    fun `x-wow-text on a value that holds no words, or not as an object, fails the schema build`() = runTest {
        mapOf(
            mapOf("type" to "integer") + text to "x-wow-text on [state.value] requires a string field or an array of strings.",
            mapOf("type" to "object", "properties" to mapOf("a" to string)) + text to
                "x-wow-text on [state.value] requires a string field or an array of strings.",
            mapOf("type" to "object", "additionalProperties" to string) + text to
                "x-wow-text on [state.value] requires a string field or an array of strings, not a map.",
            string + (ManifestQueryKeywords.TEXT to true) to
                "Invalid Manifest query keyword on [state.value]: x-wow-text must be an object.",
        ).forEach { (property, message) ->
            assertThrows<QuerySchemaConflictException>("$property") { manifestSchema(mapOf("value" to property)) }
                .message.assert().isEqualTo(message)
        }
    }

    @Test
    fun `a marked string that is matched exactly or protected fails the schema build, however it is declared`() = runTest {
        mapOf(
            string + ("enum" to listOf("A", "B")) + text to "Text of [state.value] marks an enumeration, which is matched exactly.",
            string + (ManifestQueryKeywords.TEMPORAL to mapOf("pattern" to "yyyy")) + text to
                "Text of [state.value] marks a field of a semantic type, which is matched exactly.",
            string + (ManifestQueryKeywords.REFERENCE to mapOf("aggregateName" to "member")) + text to
                "Text of [state.value] marks a field of a semantic type, which is matched exactly.",
            string + (ManifestQueryKeywords.SENSITIVE to mapOf("level" to "display")) + text to
                "Text of [state.value] marks a sensitive field, which a search would reveal.",
        ).forEach { (property, message) ->
            assertThrows<QuerySchemaConflictException>("$property") { manifestSchema(mapOf("value" to property)) }
                .message.assert().isEqualTo(message)
        }
        assertThrows<QuerySchemaConflictException> {
            codeSchema {
                field("state.byKey") {
                    values {
                        types(QueryValueType.STRING)
                        text()
                    }
                }
            }
        }.message.assert().isEqualTo("Text of [state.byKey.{key}] marks a map's values, which are matched by key.")
        assertThrows<QuerySchemaConflictException> {
            codeSchema {
                field("state.count") {
                    types(QueryValueType.INTEGER)
                    text()
                }
            }
        }.message.assert().isEqualTo("Text of [state.count] requires a string field.")
    }

    @Test
    fun `a declaration in code marks a string, and marking the state itself fails`() = runTest {
        val schema = codeSchema {
            field("state.title") {
                types(QueryValueType.STRING)
                text()
                text()
            }
            field("state.labels") {
                items {
                    types(QueryValueType.STRING)
                    text()
                }
            }
        }
        schema.value("state.title").text.assert().isTrue()
        checkNotNull(schema.value("state.labels").items).text.assert().isTrue()
        assertThrows<QuerySchemaConflictException> { codeSchema { field("state") { text() } } }
            .message.assert().isEqualTo("Text of [state] requires a string field.")
    }

    @Test
    fun `a marked string whose enumeration lists no value is a plain string`() {
        LogicalQuerySchema(
            QueryValueSchema(
                QueryValueKind.OBJECT,
                properties = mapOf(
                    "name" to QueryValueSchema(
                        QueryValueKind.SCALAR,
                        valueTypes = setOf(QueryValueType.STRING),
                        enumValues = emptyList(),
                        text = true,
                    ),
                ),
            ),
        ).value(QueryField("name").toPathTemplate())?.text.assert().isEqualTo(true)
    }

    @Test
    fun `the marker is part of a value's definition`() {
        val plain = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
        val marked = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), text = true)
        plain.sameDefinitionAs(marked).assert().isFalse()
        // Beside it on the same lines: aliases, deprecation and enumeration still tell definitions apart.
        listOf(
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                aliases = setOf(QueryField("a"))
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                deprecated = QueryDeprecation()
            ),
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                enumValues = listOf(WowJson.mapper.valueToTree("A")),
            ),
        ).forEach { other -> plain.sameDefinitionAs(other).assert().isFalse() }
        marked.sameDefinitionAs(
            QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), text = true),
        ).assert().isTrue()
        QueryFieldDeclaration(text = DeclarationValue.Set(true)).assert()
            .isNotEqualTo(QueryFieldDeclaration())
        QueryFieldDeclaration.of(text = DeclarationValue.Set(true)).toString().assert().contains("text=Set(value=true)")
    }
}
