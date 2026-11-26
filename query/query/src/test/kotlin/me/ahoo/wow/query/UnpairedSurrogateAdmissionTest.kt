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
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test

/**
 * A text operand holding an unpaired surrogate (`"\ud800"` in a JSON body) matches no text a store holds, and a
 * store's driver writes it as `?`, which a pattern reads as a quantifier and a comparison as a character the operand
 * never held (#7211). Admission refuses it on every operator that takes a text, as a value mismatch of its field; a
 * surrogate pair, a character past the Basic Multilingual Plane, is a text as any other.
 */
class UnpairedSurrogateAdmissionTest {
    private val schema: QueryModelSchema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "name" to scalarFixture(QueryValueType.STRING),
            "tags" to arrayFixture(scalarFixture(QueryValueType.STRING)),
        ),
    )
    private val name = QueryField("name")

    private fun violation(filter: FilterExpression): QueryViolation? = runCatching {
        QueryAdmission.Trusted.count(CountQuery(filter), schema)
    }.exceptionOrNull()?.let { (it as QuerySchemaValidationException).violation }

    @Test
    fun `a text operand with an unpaired surrogate should be a value mismatch on every operator taking a text`() {
        listOf("a\uD800", "\uD800b", "\uDC00", "a\uDC00\uD800b").forEach { text ->
            listOf(
                ContainsFilter(name, text),
                ContainsFilter(name, text, StringComparison.CASE_INSENSITIVE),
                StartsWithFilter(name, text, StringComparison.CASE_INSENSITIVE),
                EndsWithFilter(name, text),
                EqualFilter(name, text),
                NotEqualFilter(name, text),
                InFilter(name, listOf("ann", text)),
                BetweenFilter(name, "a", text),
            ).forEach { filter ->
                violation(filter).assert().describedAs("$filter").isEqualTo(QueryViolation.ValueMismatch(name))
            }
        }
        violation(InFilter(QueryField("tags"), listOf("x", "\uD800"))).assert()
            .isEqualTo(QueryViolation.ValueMismatch(QueryField("tags")))
        // An array's equality, its elements read as the JSON array they make.
        violation(EqualFilter(QueryField("tags"), listOf("x", "\uD800"))).assert()
            .isEqualTo(QueryViolation.ValueMismatch(QueryField("tags")))
    }

    @Test
    fun `a surrogate pair should be a text as any other`() {
        val emoji = "a😀b"
        violation(ContainsFilter(name, emoji, StringComparison.CASE_INSENSITIVE)).assert().isNull()
        violation(EqualFilter(name, emoji)).assert().isNull()
        violation(InFilter(QueryField("tags"), listOf(emoji))).assert().isNull()
    }
}
