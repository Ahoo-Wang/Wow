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
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

/** The response shapes the masker accepts around a masked value, and the ones it refuses (fail closed). */
class SchemaMaskerShapesTest {
    private val rule = MaskRule(SensitivityLevel.DISPLAY)

    private fun masker(state: QueryValueSchema): SchemaMasker = checkNotNull(
        SchemaMasker.create(
            QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), LogicalQuerySchema(obj("state" to state)), emptyMap()),
        ),
    )

    private fun obj(vararg properties: Pair<String, QueryValueSchema>) =
        QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(*properties))

    private fun map(values: QueryValueSchema) = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = values)

    private fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)

    private fun string(rule: MaskRule? = null) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), maskRule = rule)

    private fun integer() = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER))

    private fun json(text: String): ObjectNode = WowJson.mapper.readTree(text) as ObjectNode

    @Test
    fun `a value that is a masked string or an object masks only the string`() {
        val value = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(string(rule), obj("inner" to string(rule), "plain" to string())),
        )
        val masker = masker(obj("value" to value))
        masker.mask(json("""{"state":{"value":"abc"}}""")).path("state").path("value").stringValue()
            .assert().isEqualTo("***")
        masker.mask(json("""{"state":{"value":{"inner":"xy","plain":"p"}}}""")).path("state").path("value").run {
            path("inner").stringValue().assert().isEqualTo("**")
            path("plain").stringValue().assert().isEqualTo("p")
        }
        assertThrows<QueryExecutionException> { masker.mask(json("""{"state":{"value":1}}""")) }
    }

    @Test
    fun `a masked map visits each key and refuses another container`() {
        val masker = masker(obj("contacts" to map(array(string(rule)))))
        masker.mask(json("""{"state":{"contacts":{"home":["123"],"work":[]}}}""")).path("state").path("contacts")
            .path("home").path(0).stringValue().assert().isEqualTo("***")
        assertThrows<QueryExecutionException> { masker.mask(json("""{"state":{"contacts":"x"}}""")) }
        assertThrows<QueryExecutionException> { masker.mask(json("""{"state":{"contacts":{"home":[1]}}}""")) }
        assertThrows<QueryExecutionException> { masker.mask(json("""{"state":"x"}""")) }
    }

    @Test
    fun `a named key of a masked map takes both its own and the map's masks`() {
        val contacts = QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf("home" to obj("secret" to string(rule), "note" to string())),
            additionalProperties = obj("secret" to string(rule)),
        )
        val masker = masker(obj("contacts" to contacts))
        masker.mask(json("""{"state":{"contacts":{"home":{"secret":"abc","note":"n"},"work":{"secret":"de"}}}}"""))
            .path("state").path("contacts").run {
                path("home").path("secret").stringValue().assert().isEqualTo("***")
                path("home").path("note").stringValue().assert().isEqualTo("n")
                path("work").path("secret").stringValue().assert().isEqualTo("**")
            }
    }

    @Test
    fun `a map value that is an object or a plain string masks only inside the object`() {
        val values =
            QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(obj("secret" to string(rule)), string()))
        val masker = masker(obj("contacts" to map(values)))
        masker.mask(json("""{"state":{"contacts":{"a":"plain","b":{"secret":"xyz"}}}}""")).path("state")
            .path("contacts").run {
                path("a").stringValue().assert().isEqualTo("plain")
                path("b").path("secret").stringValue().assert().isEqualTo("***")
            }
    }

    @Test
    fun `each undeclared shape fails with its own reason`() {
        fun failure(state: QueryValueSchema, response: String): String? =
            runCatching { masker(state).mask(json(response)) }.exceptionOrNull()?.message
        val plain = obj("value" to string(rule), "other" to obj("x" to string()))
        failure(plain, """{"state":"x"}""").assert().isEqualTo("Masked response has an undeclared container shape.")
        failure(plain, """{"state":{"value":1}}""").assert().isEqualTo("Masked value must contain strings.")
        val either = obj(
            "value" to QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(string(rule), obj("a" to string()))),
        )
        failure(either, """{"state":{"value":[1]}}""").assert().isNotNull()
        failure(either, """{"state":{"value":true}}""").assert().isNotNull()
        val list = obj("codes" to array(string(rule)))
        failure(list, """{"state":{"codes":"x"}}""").assert().isNotNull()
        failure(list, """{"state":{"codes":[{"a":1}]}}""").assert().isNotNull()
        failure(list, """{"state":{"codes":[[1]]}}""").assert().isNotNull()
        val dynamic = obj("contacts" to map(string(rule)))
        failure(dynamic, """{"state":{"contacts":{"a":{"b":"c"}}}}""").assert().isNotNull()
        failure(dynamic, """{"state":{"contacts":{"a":1}}}""").assert().isNotNull()
        failure(dynamic, """{"state":{"contacts":[1]}}""").assert().isNotNull()
    }

    @Test
    fun `a masked array of strings or integers masks the strings only`() {
        val value = array(
            QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(string(rule), integer())),
        )
        val masker = masker(obj("codes" to value))
        masker.mask(json("""{"state":{"codes":["ab",7,null]}}""")).path("state").path("codes").run {
            path(0).stringValue().assert().isEqualTo("**")
            path(1).intValue().assert().isEqualTo(7)
        }
        masker.mask(json("""{"state":{}}""")).path("state").isEmpty.assert().isTrue()
    }
}
