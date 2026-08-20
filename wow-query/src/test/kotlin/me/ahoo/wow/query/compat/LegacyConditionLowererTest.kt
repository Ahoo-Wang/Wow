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

package me.ahoo.wow.query.compat

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.LegacyConditionExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.RelativeTimeExpression
import me.ahoo.wow.api.query.SearchExpression
import org.junit.jupiter.api.Test

class LegacyConditionLowererTest {
    @Test
    fun `should lower portable conditions`() {
        val expression = LegacyConditionLowerer.lower(
            Condition.and(
                Condition.aggregateId("aggregate-1"),
                Condition.eq("state.status", "ACTIVE")
            )
        ) as LogicalExpression

        expression.operands.assert().hasSize(2)
        (expression.operands.all { it is PredicateExpression }).assert().isTrue()
    }

    @Test
    fun `should lower relative time and full text without resolving current time`() {
        (LegacyConditionLowerer.lower(Condition.today("eventTime")) is RelativeTimeExpression).assert().isTrue()
        (LegacyConditionLowerer.lower(Condition.match("state.name", "phone")) is SearchExpression).assert().isTrue()
    }

    @Test
    fun `should preserve legacy raw query`() {
        (LegacyConditionLowerer.lower(Condition.raw("{}")) is LegacyConditionExpression).assert().isTrue()
    }
}
