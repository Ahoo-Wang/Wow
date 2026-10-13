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
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test

/**
 * An object-valued field offers no scalar domain, so admission rejects a domain operator on it whatever operand an
 * in-process caller passes, as the descriptor lists none, instead of the backend failing on the operand later.
 */
class ObjectOperandAdmissionTest {
    private val schema: QueryModelSchema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "attributes" to objectFixture("color" to scalarFixture()),
            "items" to arrayFixture(objectFixture("sku" to scalarFixture())),
            "tags" to arrayFixture(scalarFixture(QueryValueType.STRING)),
        ),
    )

    private fun violation(filter: FilterExpression): QueryViolation? = runCatching {
        QueryAdmission.Trusted.count(CountQuery(filter), schema)
    }.exceptionOrNull()?.let { (it as QuerySchemaValidationException).violation }

    @Test
    fun `a domain operator with an object operand on an object field is a value mismatch`() {
        val attributes = QueryField("attributes")
        violation(EqualFilter(attributes, Attributes("red"))).assert()
            .isEqualTo(QueryViolation.ValueMismatch(attributes))
        violation(NotEqualFilter(attributes, Attributes("red"))).assert()
            .isEqualTo(QueryViolation.ValueMismatch(attributes))
        // A string operand on the object field, or on an array of objects, matches no scalar domain either.
        violation(InFilter(attributes, listOf("red"))).assert().isEqualTo(QueryViolation.ValueMismatch(attributes))
        val items = QueryField("items")
        violation(ContainsAllFilter(items, listOf("A"))).assert().isEqualTo(QueryViolation.ValueMismatch(items))
    }

    @Test
    fun `presence and scalar domains are still admitted`() {
        schema.field(QueryField("attributes"))?.value?.kind.assert().isEqualTo(QueryValueKind.OBJECT)
        violation(IsNullFilter(QueryField("attributes"))).assert().isNull()
        violation(EqualFilter(QueryField("attributes.color"), "red")).assert().isNull()
        violation(ContainsAllFilter(QueryField("tags"), listOf("a", "b"))).assert().isNull()
    }

    /** A runtime value an in-process caller may pass: it serializes as a JSON object. */
    private data class Attributes(val color: String)
}
