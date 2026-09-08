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
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.KeepMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.isFieldProtected
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Proxy
import kotlin.reflect.jvm.javaField

class SchemaMaskerTest {
    @Test
    fun `schema without masked values uses fast path`() {
        SchemaMasker.create(schema(obj())).assert().isNull()
    }

    @Test
    fun `typed map and array traversal masks dotted response keys once`() {
        val contacts = QueryValueSchema(
            QueryValueKind.OBJECT,
            additionalProperties = array(obj("secret" to string(fullMaskRule())))
        )
        val masker = SchemaMasker.create(schema(obj("contacts" to contacts)))!!
        val node = """{"state":{"contacts":{"odd.key":[{"secret":"hello"}],"other":[{"secret":null}]}}}""".toJsonNode<ObjectNode>()
        masker.mask(node).assert().isSameAs(node)
        node.path(
            "state"
        ).path("contacts").path("odd.key").path(0).path("secret").stringValue().assert().isEqualTo("*****")
        node.path("state").path("contacts").path("other").path(0).path("secret").isNull.assert().isTrue()
    }

    @Test
    fun `sparse masked values read shared branch once`() {
        val masker = SchemaMasker.create(
            schema(
                obj(
                    *(0 until 64).map {
                        "secret$it" to string(fullMaskRule())
                    }.toTypedArray()
                )
            )
        )!!
        val state = CountingObjectNode().also { it.put("secret63", "value") }
        val node = CountingObjectNode().also { it.set("state", state) }
        masker.mask(node)
        node.getCalls.assert().isOne()
        state.getCalls.assert().isZero()
        state.path("secret63").stringValue().assert().isEqualTo("*****")
    }

    @Test
    fun `array mask applies once to recursive string members`() {
        val nested = QueryValueSchema(QueryValueKind.ARRAY, items = array(string()), maskRule = fullMaskRule())
        val masker = SchemaMasker.create(schema(obj("secret" to nested)))!!
        val node = """{"state":{"secret":[["abc",null,""]]}}""".toJsonNode<ObjectNode>()
        masker.mask(node)
        node.path("state").path("secret").path(0).path(0).stringValue().assert().isEqualTo("***")
    }

    @Test
    fun `masked union string preserves explicitly declared integer branch`() {
        val value = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(
                string(fullMaskRule()),
                QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER))
            )
        )
        val masker = SchemaMasker.create(schema(obj("value" to value)))!!
        val number = """{"state":{"value":42}}""".toJsonNode<ObjectNode>()
        masker.mask(number).path("state").path("value").intValue().assert().isEqualTo(42)
        val text = """{"state":{"value":"abc"}}""".toJsonNode<ObjectNode>()
        masker.mask(text).path("state").path("value").stringValue().assert().isEqualTo("***")
        assertThrows<QuerySchemaValidationException> {
            masker.mask(
                """{"state":{"value":true}}""".toJsonNode<ObjectNode>()
            )
        }
    }

    @Test
    fun `same physical source aliases are both masked and equal response is masked once`() {
        val rule = fullMaskRule().copyWith(CompiledMask { "[$it]" })
        val root = obj("secret" to string(rule), "alias" to string())
        val shared = path("stored.secret")
        val bindings = listOf("secret", "alias").associate { name ->
            path("state.$name") to QueryValueBindings(
                mapOf(QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(shared, null)),
                responsePath = path("state.$name"),
            )
        }
        val masker = SchemaMasker.create(schema(root, bindings))!!
        val node = """{"state":{"secret":"abc","alias":"abc"}}""".toJsonNode<ObjectNode>()
        masker.mask(node)
        node.path("state").path("secret").stringValue().assert().isEqualTo("[abc]")
        node.path("state").path("alias").stringValue().assert().isEqualTo("[abc]")
    }

    @Test
    fun `invalid string and ancestor wire shapes fail closed`() {
        val masker = SchemaMasker.create(schema(obj("secret" to string(fullMaskRule()))))!!
        listOf("""{"state":{"secret":2}}""", """{"state":2}""", """{"state":{"secret":["abc"]}}""").forEach {
            assertThrows<QuerySchemaValidationException> { masker.mask(it.toJsonNode<ObjectNode>()) }
        }
    }

    @Test
    fun `invalid mask publication and response destination fail before execution`() {
        assertThrows<QuerySchemaConflictException> {
            schema(obj("secret" to QueryValueSchema(QueryValueKind.UNKNOWN, maskRule = fullMaskRule())))
        }
        assertThrows<QuerySchemaConflictException> {
            schema(
                obj("secret" to string(fullMaskRule())),
                mapOf(path("state.secret") to QueryValueBindings(responsePath = path("outside")))
            )
        }
    }

    @Test
    fun `custom mask failures are contained while fatal errors propagate`() {
        listOf(
            compiledMaskReturningNull(),
            CompiledMask { throw IllegalStateException("sensitive") }
        ).forEach { compiled ->
            val masker = SchemaMasker.create(schema(obj("secret" to string(fullMaskRule().copyWith(compiled)))))!!
            assertThrows<QuerySchemaValidationException> {
                masker.mask(
                    """{"state":{"secret":"abc"}}""".toJsonNode<ObjectNode>()
                )
            }
        }
        val masker = SchemaMasker.create(
            schema(
                obj(
                    "secret" to string(
                        fullMaskRule().copyWith(
                            CompiledMask {
                                throw LinkageError("fatal")
                            }
                        )
                    )
                )
            )
        )!!
        assertThrows<LinkageError> { masker.mask("""{"state":{"secret":"abc"}}""".toJsonNode<ObjectNode>()) }
    }

    @Test
    fun `event type and element shape are checked but metadata only results remain valid`() {
        val definition = LogicalQuerySchema(
            obj(
                "body" to array(
                    obj(
                        "bodyType" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), enumValues = listOf(JsonNodeFactory.instance.stringNode("Known"))),
                        "body" to obj("secret" to string(fullMaskRule())),
                    )
                )
            )
        )
        val masker = SchemaMasker.create(
            QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), definition, emptyMap())
        )!!
        listOf("{}", """{"body":null}""", """{"body":[{"id":"one"}]}""", """{"body":[{"body":null}]}""").forEach {
            masker.mask(it.toJsonNode<ObjectNode>())
        }
        listOf(
            """{"body":{}}""",
            """{"body":[null]}""",
            """{"body":["event"]}""",
            """{"body":[{"body":{"secret":"abc"}}]}""",
            """{"body":[{"bodyType":"Unknown","body":{"secret":"abc"}}]}"""
        ).forEach {
            assertThrows<QuerySchemaValidationException> { masker.mask(it.toJsonNode<ObjectNode>()) }
        }
        val node = """{"body":[{"bodyType":"Known","body":{"secret":"abc"}}]}""".toJsonNode<ObjectNode>()
        masker.mask(node).path("body").path(0).path("body").path("secret").stringValue().assert().isEqualTo("***")
    }

    @Test
    fun `named map values shadow the protected additional value`() {
        val map = QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf(
                "public" to string(),
                "number" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER)),
            ),
            additionalProperties = string(fullMaskRule())
        )
        val schema = schema(obj("map" to map))
        isFieldProtected(schema, path("state.map.public")).assert().isFalse()
        val node = """{"state":{"map":{"public":"visible","number":42,"other":"secret"}}}""".toJsonNode<ObjectNode>()
        SchemaMasker.create(schema)!!.mask(node)
        node.path("state").path("map").path("public").stringValue().assert().isEqualTo("visible")
        node.path("state").path("map").path("number").intValue().assert().isEqualTo(42)
        node.path("state").path("map").path("other").stringValue().assert().isEqualTo("******")
    }

    @Test
    fun `container source alias retains mask across physical array flattening`() {
        val state = obj("orders" to array(obj("secret" to string(fullMaskRule()))), "alias" to array(string()))
        val bindings = mapOf(
            path("state.orders") to QueryValueBindings(mapOf(QueryCapability.PRESENCE to QueryFieldBindingTemplate(path("stored.orders"), null)), responsePath = path("state.orders")),
            path("state.alias") to QueryValueBindings(mapOf(QueryCapability.PRESENCE to QueryFieldBindingTemplate(path("stored.orders.secret"), null)), responsePath = path("state.alias")),
        )
        val schema = schema(state, bindings)
        isFieldProtected(schema, path("state.alias")).assert().isTrue()
        val node = """{"state":{"orders":[{"secret":"leak"}],"alias":["leak"]}}""".toJsonNode<ObjectNode>()
        SchemaMasker.create(schema)!!.mask(node)
        node.path("state").path("alias").path(0).stringValue().assert().isEqualTo("****")
    }

    @Test
    fun `unknown alternate wire shape cannot bypass declared mask`() {
        assertThrows<QuerySchemaConflictException> {
            schema(
                obj(
                    "value" to QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(string(fullMaskRule()), QueryValueSchema(QueryValueKind.UNKNOWN)))
                )
            )
        }
    }

    @Test
    fun `array aliases retain mixed member domains and every array layer`() {
        val integer = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER))
        val mixed = QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(string(fullMaskRule()), integer))
        val cases = listOf(
            Triple(
                mixed,
                array(QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(string(), integer))),
                "[\"leak\",42]"
            ),
            Triple(
                QueryValueSchema(QueryValueKind.ARRAY, items = string(), maskRule = fullMaskRule()),
                array(array(string())),
                "[[\"leak\"]]"
            ),
        )
        cases.forEach { (source, alias, json) ->
            val state = obj("orders" to array(obj("secret" to source)), "alias" to alias)
            val bindings = mapOf(
                path("state.orders") to QueryValueBindings(mapOf(QueryCapability.PRESENCE to QueryFieldBindingTemplate(path("stored.orders"), null)), responsePath = path("state.orders")),
                path("state.alias") to QueryValueBindings(mapOf(QueryCapability.PRESENCE to QueryFieldBindingTemplate(path("stored.orders.secret"), null)), responsePath = path("state.alias")),
            )
            val node = ("{\"state\":{\"alias\":" + json + "}}").toJsonNode<ObjectNode>()
            SchemaMasker.create(schema(state, bindings))!!.mask(node)
            node.toString().assert().doesNotContain("leak").contains("****")
            if (json.contains("42")) node.toString().assert().contains("42")
        }
    }

    private fun schema(state: QueryValueSchema, bindings: Map<QueryPathTemplate, QueryValueBindings> = emptyMap()) =
        QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), LogicalQuerySchema(obj("state" to state)), bindings)
    private fun obj(vararg properties: Pair<String, QueryValueSchema>) = QueryValueSchema(
        QueryValueKind.OBJECT,
        properties = mapOf(*properties)
    )
    private fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)
    private fun string(rule: MaskRule? = null) = QueryValueSchema(
        QueryValueKind.SCALAR,
        valueTypes = setOf(QueryValueType.STRING),
        maskRule = rule
    )
    private fun path(value: String) = QueryPathTemplate(value.split('.').map(QueryPathSegment::Property))

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

    private class CountingObjectNode : ObjectNode(JsonNodeFactory.instance) {
        var getCalls: Int = 0

        override fun get(propertyName: String): JsonNode? {
            getCalls++
            return super.get(propertyName)
        }
    }

    private data class Masked(
        @field:Mask val secret: String,
        @field:KeepMask(prefix = 3, suffix = 4) val phone: String,
    )
}
