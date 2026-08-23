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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.*
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneOffset

class FilterDslTest {
    @Test
    fun `should build implicit AND filter`() {
        val expression = filter {
            deletion(DeletionState.ACTIVE)
            "state.status" eq "PAID"
            or {
                "state.ownerId" eq "owner-1"
                "state.ownerId".notExists()
            }
        }

        expression.assert().isInstanceOf(AndFilter::class.java)
        (expression as AndFilter).operands.assert().hasSize(3)
    }

    @Test
    fun `should build relative element fields`() {
        val expression = filter {
            "state.items".elementMatch {
                "productId" eq "product-1"
            }
        }

        val element = expression as ElementMatchFilter
        element.field.assert().isEqualTo(LogicalField("state.items"))
        (element.predicate as EqualFilter).field.assert().isEqualTo(LogicalField("productId"))
    }

    @Test
    fun `should reject empty logical block`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter { and { } }
        }
    }

    @Test
    fun `should expose the complete filter DSL`() {
        val expression = filter {
            matchAll()
            matchNone()
            deletion(DeletionState.DELETED)
            and { "and" eq 1 }
            or { "or" eq 1 }
            nor { "nor" eq 1 }
            "state".nested { "name" eq "Wow" }
            "items".elementMatch { "quantity" gt 0 }
            "null" eq null
            "notNull" ne null
            "equal" eq 1
            "notEqual" ne 1
            "greaterThan" gt 1
            "greaterThanOrEqual" gte 1
            "lessThan" lt 1
            "lessThanOrEqual" lte 1
            "contains".contains("value", StringComparison.CASE_INSENSITIVE)
            "startsWith".startsWith("value")
            "endsWith".endsWith("value")
            "in" isIn listOf(1, 2)
            "notIn" notIn listOf(1, 2)
            "between".between(1, 2)
            "containsAll" containsAll listOf(1, 2)
            "empty".isEmptyCollection()
            "isNull".isNull()
            "isNotNull".isNotNull()
            "exists".exists()
            "notExists".notExists()
            search("phrase", "title", "description")
            "content" search "phrase"
            "today".today(ZoneOffset.UTC, "yyyy-MM-dd")
            "beforeToday".beforeToday(LocalTime.NOON, ZoneOffset.UTC)
            "tomorrow".tomorrow()
            "thisWeek".thisWeek()
            "nextWeek".nextWeek()
            "lastWeek".lastWeek()
            "thisMonth".thisMonth()
            "lastMonth".lastMonth()
            "recentDays".recentDays(2)
            "earlierDays".earlierDays(2)
        } as AndFilter

        expression.operands.assert().hasSize(40)
        (expression.operands[6] as EqualFilter).field.assert().isEqualTo(LogicalField("state.name"))
    }
}
