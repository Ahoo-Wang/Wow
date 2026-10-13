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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MaskRuleTest {
    @Test
    fun `masks are checked when declared`() {
        assertThrows<IllegalArgumentException> { QueryMask.Keep(prefix = -1) }
        assertThrows<IllegalArgumentException> { QueryMask.Keep(suffix = -1) }
        assertThrows<IllegalArgumentException> { QueryMask.Strategy(" ") }
        QueryMask.FULL.assert().isEqualTo(QueryMask.Keep(0, 0))
    }

    @Test
    fun `a rule is its level and mask, whatever strategy instance it compiled`() {
        val resolver = MaskStrategyResolver.of(mapOf("x" to MaskStrategy { "#" }))
        val rule = MaskRule(SensitivityLevel.DISPLAY, QueryMask.Strategy("x"), resolver)
        rule.compiled.mask("abc").assert().isEqualTo("#")
        rule.assert().isEqualTo(MaskRule(SensitivityLevel.DISPLAY, QueryMask.Strategy("x"), resolver))
        rule.hashCode().assert().isEqualTo(
            MaskRule(SensitivityLevel.DISPLAY, QueryMask.Strategy("x"), resolver).hashCode()
        )
        rule.assert().isNotEqualTo(MaskRule(SensitivityLevel.CONFIDENTIAL, QueryMask.Strategy("x"), resolver))
        rule.assert().isNotEqualTo(MaskRule(SensitivityLevel.DISPLAY))
        (rule as Any).assert().isNotEqualTo("x")
        rule.toString().assert().contains("DISPLAY")
        MaskStrategyResolver.NONE.resolve("x").assert().isNull()
        MaskRule(SensitivityLevel.CONFIDENTIAL, QueryMask.Keep(1, 1)).compiled.mask("abcd").assert().isEqualTo("a**d")
    }

    @Test
    fun `the sensitivity policy decides which levels compare`() {
        QuerySensitivityPolicy.DEFAULT.run {
            comparable(null).assert().isTrue()
            comparable(SensitivityLevel.DISPLAY).assert().isTrue()
            comparable(SensitivityLevel.CONFIDENTIAL).assert().isFalse()
        }
        QuerySensitivityPolicy(displayComparable = false).comparable(SensitivityLevel.DISPLAY).assert().isFalse()
    }
}
