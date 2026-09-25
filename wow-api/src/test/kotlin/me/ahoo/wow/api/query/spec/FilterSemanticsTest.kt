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

package me.ahoo.wow.api.query.spec

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FilterSemanticsTest {
    @Test
    fun `every field operator is covered by the matrix or listed with a reason`() {
        val fieldOperators = FilterOperator.entries.filter {
            FilterOperatorSpec.of(it).target in setOf(OperatorTarget.FIELD, OperatorTarget.MODEL_OR_FIELDS)
        }.toSet()
        val covered = FilterSemantics.CASES.map { it.operator }.toSet()

        (covered + FilterSemantics.UNCOVERED.keys).assert().isEqualTo(fieldOperators)
        covered.intersect(FilterSemantics.UNCOVERED.keys).assert().isEmpty()
    }

    @Test
    fun `every case covers null and missing on its field`() {
        FilterSemantics.CASES.forEach { case ->
            val probes = checkNotNull(SemanticProbe.ALL[case.shape]).map { it.name }
            probes.assert().contains(SemanticProbe.MISSING, SemanticProbe.NULL)
            case.filter(QueryField("state.probe")).operator.assert().isEqualTo(case.operator)
        }
    }

    @Test
    fun `probes separate a missing field from an explicit null`() {
        SemanticProbe.ALL.values.forEach { probes ->
            probes.single { it.name == SemanticProbe.MISSING }.value.assert().isNull()
            requireNotNull(probes.single { it.name == SemanticProbe.NULL }.value).isNull.assert().isTrue()
            probes.map { it.name }.distinct().assert().hasSameSizeAs(probes)
        }
    }

    @Test
    fun `a case cannot name probes of another shape`() {
        assertThrows<IllegalArgumentException> {
            SemanticCase("bad", SemanticShape.NUMBER, setOf(SemanticProbe.CASE_VARIANT)) {
                me.ahoo.wow.api.query.IsNullFilter(it)
            }
        }
    }
}
