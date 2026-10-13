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
import tools.jackson.databind.node.JsonNodeFactory
import java.util.Date
import java.util.UUID

class QueryOperandsTest {
    private val nodes = JsonNodeFactory.instance

    @Test
    fun `scalars and scalar arrays become driver values`() {
        null.operandValue().assert().isNull()
        "a".operandValue().assert().isEqualTo("a")
        3.operandValue().assert().isEqualTo(3)
        true.operandValue().assert().isEqualTo(true)
        listOf("a", 2).operandValue().assert().isEqualTo(listOf("a", 2))
        2.5.requiredOperandValue().assert().isEqualTo(2.5)
        1.5f.requiredOperandValue().assert().isEqualTo(1.5f)
    }

    @Test
    fun `json trees become driver values too`() {
        nodes.nullNode().operandValue().assert().isNull()
        nodes.stringNode("a").operandValue().assert().isEqualTo("a")
        nodes.numberNode(3).operandValue().assert().isEqualTo(3)
        nodes.booleanNode(true).operandValue().assert().isEqualTo(true)
        nodes.arrayNode().add("a").add(2).operandValue().assert().isEqualTo(listOf("a", 2))
        nodes.numberNode(2.5).requiredOperandValue().assert().isEqualTo(2.5)
    }

    @Test
    fun `a runtime value is compared as its JSON unless the driver encodes it natively`() {
        val uuid = UUID.fromString("f0191fbe-b181-4531-84be-4e8609e32966")
        uuid.requiredOperandValue().assert().isEqualTo(uuid.toString())
        uuid.operandValue().assert().isEqualTo(uuid.toString())

        val date = Date(1_000)
        date.operandValue(RuntimeOperands.NATIVE).assert().isSameAs(date)
        date.requiredOperandValue(RuntimeOperands.NATIVE).assert().isSameAs(date)
        listOf(date).operandValue(RuntimeOperands.NATIVE).assert().isEqualTo(listOf(date))
        date.operandValue().assert().isNotSameAs(date)
        val bean = Bean("a")
        bean.requiredOperandValue(RuntimeOperands.NATIVE).assert().isSameAs(bean)
    }

    @Test
    fun `a bad operand is a client violation, never a server fault`() {
        rejects { nodes.objectNode().put("a", 1).operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { mapOf("a" to 1).operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { setOf("a").operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { arrayOf("a").operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { byteArrayOf(1).operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { Bean("a").operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { Unserializable().operandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { listOf("a").requiredOperandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { listOf("a").requiredOperandValue(RuntimeOperands.NATIVE) }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("non-scalar filter operands"))
        rejects { null.requiredOperandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("null range or term filter operands"))
        rejects { nodes.nullNode().requiredOperandValue() }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("null range or term filter operands"))
        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            rejects { value.operandValue(RuntimeOperands.NATIVE) }
                .assert().isEqualTo(QueryViolation.StorageUnsupported("non-finite numeric filter operands"))
            rejects { nodes.numberNode(value).operandValue() }
                .assert().isEqualTo(QueryViolation.StorageUnsupported("non-finite numeric filter operands"))
        }
        rejects { Float.NEGATIVE_INFINITY.operandValue() }
            .code.assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
    }

    @Test
    fun `string comparison says whether it ignores case`() {
        StringComparison.CASE_INSENSITIVE.ignoreCase.assert().isTrue()
        StringComparison.CASE_SENSITIVE.ignoreCase.assert().isFalse()
    }

    private fun rejects(read: () -> Any?): QueryViolation =
        checkNotNull(assertThrows<QuerySchemaValidationException> { read() }.violation)

    private data class Bean(val name: String)

    /** A runtime value Jackson cannot write. */
    private class Unserializable {
        @Suppress("unused")
        val self: Unserializable
            get() = this
    }
}
