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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.util.Date
import java.util.UUID

class QueryOperandsTest {
    private val nodes = JsonNodeFactory.instance

    @Test
    fun `scalars and scalar arrays become driver values`() {
        nodes.nullNode().operandValue().assert().isNull()
        nodes.stringNode("a").operandValue().assert().isEqualTo("a")
        nodes.numberNode(3).operandValue().assert().isEqualTo(3)
        nodes.booleanNode(true).operandValue().assert().isEqualTo(true)
        nodes.arrayNode().add("a").add(2).operandValue().assert().isEqualTo(listOf("a", 2))
        nodes.numberNode(2.5).requiredOperandValue().assert().isEqualTo(2.5)
    }

    @Test
    fun `a runtime POJO is compared as its JSON unless the driver encodes it natively`() {
        val uuid = UUID.fromString("f0191fbe-b181-4531-84be-4e8609e32966")
        nodes.pojoNode(uuid).requiredOperandValue().assert().isEqualTo(uuid.toString())
        nodes.pojoNode(listOf("a", "b")).operandValue().assert().isEqualTo(listOf("a", "b"))

        val date = Date(1_000)
        nodes.pojoNode(date).operandValue(PojoOperands.NATIVE).assert().isSameAs(date)
        nodes.pojoNode(date).requiredOperandValue(PojoOperands.NATIVE).assert().isSameAs(date)
        nodes.arrayNode().add(nodes.pojoNode(date)).operandValue(PojoOperands.NATIVE).assert().isEqualTo(listOf(date))
    }

    @Test
    fun `a bad operand is a client violation, never a server fault`() {
        rejects(nodes.objectNode().put("a", 1)) { operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects(nodes.pojoNode(mapOf("a" to 1))) { operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects(nodes.arrayNode().add("a")) { requiredOperandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects(nodes.nullNode()) { requiredOperandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("null range or term filter operands"))
        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            rejects(nodes.numberNode(value)) { operandValue(PojoOperands.NATIVE) }
                .assert().isEqualTo(QueryViolation.StorageUnsupported("non-finite numeric filter operands"))
        }
        rejects(nodes.numberNode(Float.NEGATIVE_INFINITY)) { operandValue() }
            .code.assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
    }

    @Test
    fun `string comparison says whether it ignores case`() {
        StringComparison.CASE_INSENSITIVE.ignoreCase.assert().isTrue()
        StringComparison.CASE_SENSITIVE.ignoreCase.assert().isFalse()
    }

    private fun rejects(node: JsonNode, read: JsonNode.() -> Any?): QueryViolation =
        checkNotNull(assertThrows<QuerySchemaValidationException> { node.read() }.violation)
}
