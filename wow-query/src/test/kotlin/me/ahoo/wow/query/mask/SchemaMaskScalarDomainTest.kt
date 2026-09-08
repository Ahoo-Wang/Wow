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
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

class SchemaMaskScalarDomainTest {
    @Test
    fun `scalar membership retains numeric boolean nullable and union response domains`() {
        val annotation = Mask()
        val rule = MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
        val masked = scalarFixture(mask = rule)
        val cases = listOf(
            Triple(
                setOf(QueryValueType.INTEGER),
                listOf("42", "-7", "9223372036854775808"),
                listOf("1.0", "1.25", "true")
            ),
            Triple(setOf(QueryValueType.DECIMAL), listOf("42", "-7", "1.0", "1.25", "1e3"), listOf("true")),
            Triple(setOf(QueryValueType.BOOLEAN), listOf("true", "false"), listOf("42", "1.25")),
            Triple(setOf(QueryValueType.INTEGER, QueryValueType.BOOLEAN), listOf("42", "true"), listOf("1.0")),
            Triple(setOf(QueryValueType("CUSTOM")), emptyList(), listOf("42", "1.25", "true")),
        )
        cases.forEach { (types, accepted, rejected) ->
            listOf(false, true).forEach { nullable ->
                val scalar = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = types, nullable = nullable)
                val union = QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(masked, scalar))
                val masker = masker(union)
                masker.mask(document("\"abc\"")).path("state").path("value").stringValue().assert().isEqualTo("***")
                (accepted + listOf("null")).forEach { json ->
                    // Mask preserves null values; it is not a general nullability validator.
                    val node = document(json)
                    val expected = node.deepCopy()
                    masker.mask(node).assert().isEqualTo(expected)
                }
                (rejected + listOf("{}", "[]")).forEach { json ->
                    assertThrows<QuerySchemaValidationException> { masker.mask(document(json)) }
                }
            }
        }
        val nullBranch = QueryValueSchema(QueryValueKind.NULL)
        val nested = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(
                QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(masked, nullBranch)),
                scalarFixture(QueryValueType.BOOLEAN),
            ),
        )
        val nestedMasker = masker(nested)
        listOf("null", "true").forEach { json ->
            nestedMasker.mask(document(json)).assert().isEqualTo(document(json))
        }
        nestedMasker.mask(document("\"abc\"")).path("state").path("value").stringValue().assert().isEqualTo("***")
        assertThrows<QuerySchemaValidationException> { nestedMasker.mask(document("1")) }
    }

    private fun masker(value: QueryValueSchema): SchemaMasker = checkNotNull(
        SchemaMasker.create(boundSchemaFixture(objectFixture("state" to objectFixture("value" to value)))),
    )

    private fun document(value: String): ObjectNode = ("{\"state\":{\"value\":" + value + "}}").toJsonNode()
}
