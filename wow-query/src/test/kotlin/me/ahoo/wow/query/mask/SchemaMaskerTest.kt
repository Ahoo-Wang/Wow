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

package me.ahoo.wow.query.mask

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.KeepMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Proxy
import kotlin.reflect.jvm.javaField

class SchemaMaskerTest {
    @Test
    fun `schema without masked fields should use fast path`() {
        SchemaMasker.create(
            QueryModelSchema(
                model = QueryModel.SNAPSHOT,
                capabilities = emptySet(),
                fields = emptyMap(),
            ),
        ).assert().isNull()
    }

    @Test
    fun `snapshot should mask strings recursively in place`() {
        val masker = SchemaMasker.create(
            schema(
                QueryModel.SNAPSHOT,
                "state.secret" to fullMaskRule(),
                "state.phone" to keepMaskRule(),
                "state.profiles.phones" to fullMaskRule(),
            ),
        )!!
        val node = """
            {
              "state": {
                "secret": "密码🙂",
                "phone": "13800138000",
                "profiles": [
                  {"phones": ["12345", null, ""]},
                  {"missing": true}
                ]
              }
            }
        """.toJsonNode<ObjectNode>()

        val masked = masker.mask(node)

        masked.assert().isSameAs(node)
        node.path("state").path("secret").stringValue().assert().isEqualTo("***")
        node.path("state").path("phone").stringValue().assert().isEqualTo("138****8000")
        node.path("state").path("profiles").path(0).path("phones").path(0).stringValue()
            .assert().isEqualTo("*****")
        node.path("state").path("profiles").path(0).path("phones").path(1).isNull.assert().isTrue()
        node.path("state").path("profiles").path(0).path("phones").path(2).stringValue()
            .assert().isEmpty()
    }

    @Test
    fun `snapshot should mask the projection path returned by the backend`() {
        val masker = SchemaMasker.create(
            QueryModelSchema(
                model = QueryModel.SNAPSHOT,
                capabilities = emptySet(),
                fields = mapOf(
                    LogicalField("state.emailAlias") to fieldSchema(
                        projectionPath = "state.email",
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )!!
        val node = """{"state":{"email":"secret@example.com"}}""".toJsonNode<ObjectNode>()

        masker.mask(node)

        node.path("state").path("email").stringValue().assert().isEqualTo("******************")
    }

    @Test
    fun `snapshot should reject masked fields outside state`() {
        assertThrows<QuerySchemaConflictException> {
            SchemaMasker.create(schema(QueryModel.SNAPSHOT, "secret" to fullMaskRule()))
        }
    }

    @Test
    fun `snapshot should reject projection paths outside state`() {
        assertThrows<QuerySchemaConflictException> {
            SchemaMasker.create(
                QueryModelSchema(
                    model = QueryModel.SNAPSHOT,
                    capabilities = emptySet(),
                    fields = mapOf(
                        LogicalField("state.secret") to fieldSchema(
                            projectionPath = "secret",
                            maskRule = fullMaskRule(),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `snapshot should reject non-string wire values`() {
        val masker = SchemaMasker.create(schema(QueryModel.SNAPSHOT, "state.secret" to fullMaskRule()))!!
        val node = """{"state":{"secret":42}}""".toJsonNode<ObjectNode>()

        assertThrows<QuerySchemaValidationException> { masker.mask(node) }
    }

    @Test
    fun `snapshot should reject a custom mask returning null`() {
        val rule = fullMaskRule().let { original ->
            original.copyWith(compiledMaskReturningNull())
        }
        val masker = SchemaMasker.create(schema(QueryModel.SNAPSHOT, "state.secret" to rule))!!

        assertThrows<QuerySchemaValidationException> {
            masker.mask("""{"state":{"secret":"value"}}""".toJsonNode<ObjectNode>())
        }
    }

    @Test
    fun `event stream should validate body type and mask nested arrays`() {
        val masker = SchemaMasker.create(
            eventSchema("body.body.customers.secrets" to fullMaskRule()),
        )!!
        val node = """
            {
              "body": [
                {
                  "bodyType": "example.KnownEvent",
                  "body": {"customers": [{"secrets": ["one", "二"]}]}
                }
              ]
            }
        """.toJsonNode<ObjectNode>()

        masker.mask(node)

        val secrets = node.path("body").path(0).path("body").path("customers").path(0).path("secrets")
        secrets.path(0).stringValue().assert().isEqualTo("***")
        secrets.path(1).stringValue().assert().isEqualTo("*")
    }

    @Test
    fun `event stream should fail closed for unknown or missing body type`() {
        val masker = SchemaMasker.create(eventSchema("body.body.secret" to fullMaskRule()))!!

        listOf(
            """{"body":[{"bodyType":"example.UnknownEvent","body":{"secret":"one"}}]}""",
            """{"body":[{"body":{"secret":"one"}}]}""",
        ).forEach { json ->
            assertThrows<QuerySchemaValidationException> {
                masker.mask(json.toJsonNode<ObjectNode>())
            }
        }
    }

    @Test
    fun `event stream should preserve projected results with missing or null body`() {
        val masker = SchemaMasker.create(eventSchema("body.body.secret" to fullMaskRule()))!!

        listOf("{}", """{"body":null}""").forEach { json ->
            val node = json.toJsonNode<ObjectNode>()

            masker.mask(node).assert().isSameAs(node)
        }
    }

    @Test
    fun `event stream should fail closed for non-array body`() {
        val masker = SchemaMasker.create(eventSchema("body.body.secret" to fullMaskRule()))!!

        assertThrows<QuerySchemaValidationException> {
            masker.mask("""{"body":{}}""".toJsonNode<ObjectNode>())
        }
    }

    private fun schema(model: QueryModel, vararg fields: Pair<String, MaskRule>): QueryModelSchema =
        QueryModelSchema(
            model = model,
            capabilities = emptySet(),
            fields = fields.associate { (field, rule) ->
                LogicalField(field) to fieldSchema(maskRule = rule)
            },
        )

    private fun eventSchema(vararg fields: Pair<String, MaskRule>): QueryModelSchema =
        schema(QueryModel.EVENT_STREAM, *fields).let { schema ->
            schema.copy(
                fields = schema.fields + Pair(
                    LogicalField("body.bodyType"),
                    fieldSchema(enumValues = listOf(JsonSerializer.valueToTree("example.KnownEvent"))),
                ),
            )
        }

    private fun fieldSchema(
        enumValues: List<tools.jackson.databind.JsonNode>? = null,
        projectionPath: String? = null,
        maskRule: MaskRule? = null,
    ) = QueryFieldSchema(
        title = null,
        description = null,
        enumValues = enumValues,
        valueTypes = setOf(QueryValueType.STRING),
        nullable = true,
        required = false,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = false,
        bindings = emptyMap(),
        projectionPath = projectionPath,
        maskRule = maskRule,
    )

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private fun keepMaskRule(): MaskRule {
        val annotation = Masked::phone.javaField!!.getAnnotation(KeepMask::class.java)
        return MaskRule(KeepMaskStrategy::class, annotation, KeepMaskStrategy.compile(annotation))
    }

    private fun MaskRule.copyWith(compiled: CompiledMask): MaskRule =
        MaskRule(strategyType, annotation, compiled)

    private fun compiledMaskReturningNull(): CompiledMask = Proxy.newProxyInstance(
        CompiledMask::class.java.classLoader,
        arrayOf(CompiledMask::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "mask" -> null
            "toString" -> "NullCompiledMask"
            else -> error("Unexpected method: ${method.name}")
        }
    } as CompiledMask

    private data class Masked(
        @field:Mask val secret: String,
        @field:KeepMask(prefix = 3, suffix = 4) val phone: String,
    )
}
