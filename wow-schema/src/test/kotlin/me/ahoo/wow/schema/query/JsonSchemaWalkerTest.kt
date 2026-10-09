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

package me.ahoo.wow.schema.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryTypeFact
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/** Reference and composition edge cases that generated schemas rarely contain, written by hand. */
class JsonSchemaWalkerTest {
    private fun fact(schema: String): QueryTypeFact =
        JsonSchemaWalker(schema = schema.toObject<ObjectNode>(), memberResolver = { error("no members") }).fact()

    @Test
    fun `a reference to a missing definition is an unknown value`() {
        val fact = fact(
            """{"type":"object","properties":{"value":{"${'$'}ref":"#/${'$'}defs/Missing","title":"Value"}}}"""
        )
        val value = fact.properties.getValue("value")
        value.kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
        value.title.assert().isEqualTo("Value")
    }

    @Test
    fun `a recursive reference is not expanded again`() {
        val fact = fact(
            """{"${'$'}defs":{"Node":{"type":"object","title":"Node",""" +
                """"properties":{"next":{"${'$'}ref":"#/${'$'}defs/Node"},""" +
                """"ghost":{"${'$'}ref":"#/${'$'}defs/Missing"}}}},"${'$'}ref":"#/${'$'}defs/Node"}""",
        )
        fact.kind.assert().isEqualTo(QueryValueKind.OBJECT)
        fact.title.assert().isEqualTo("Node")
        val next = fact.properties.getValue("next")
        next.kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
        next.omitted.assert().isEmpty()
        fact.properties.getValue("ghost").kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
    }

    @Test
    fun `a property write-only through its reference or composition is not serialized`() {
        val fact = fact(
            """{"${'$'}defs":{"Secret":{"type":"string","writeOnly":true}},"type":"object","properties":{""" +
                """"byReference":{"${'$'}ref":"#/${'$'}defs/Secret"},""" +
                """"byComposition":{"allOf":[{"type":"string","writeOnly":true}]},""" +
                """"byMissingReference":{"${'$'}ref":"#/${'$'}defs/Missing"},""" +
                """"notWriteOnly":{"type":"string","writeOnly":false},""" +
                """"textualWriteOnly":{"type":"string","writeOnly":"true"},""" +
                """"plain":{"type":"string"}}}""",
        )
        fact.properties.keys.assert().containsExactly("byMissingReference", "notWriteOnly", "textualWriteOnly", "plain")
    }

    @Test
    fun `metadata reached through a composition branch is collected`() {
        val fact = fact(
            """{"type":"object","properties":{"value":{"anyOf":[{"type":"null"},""" +
                """{"type":"string","description":"From branch"}]}}}""",
        )
        fact.properties.getValue("value").description.assert().isEqualTo("From branch")
    }
}
