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
import me.ahoo.wow.query.snapshot.pathState
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class FilterDslTest {
    @Test
    fun `should build phrase search`() {
        val expression = filter {
            search("event sourcing", SearchMode.PHRASE, "title", "description")
        }

        expression.assert().isEqualTo(
            SearchFilter(
                query = "event sourcing",
                fields = linkedSetOf(LogicalField("title"), LogicalField("description")),
                mode = SearchMode.PHRASE,
            ),
        )
    }

    @Test
    fun `should build extended relative calendar filters`() {
        val type = FieldType.Temporal.String(datePattern = "yyyy-MM-dd")
        val field = LogicalField("createdAt", type)
        val expression = filter {
            "createdAt".yesterday(type, ZoneOffset.UTC)
            "createdAt".nextMonth(type, ZoneOffset.UTC)
            "createdAt".lastYear(type, ZoneOffset.UTC)
            "createdAt".thisYear(type, ZoneOffset.UTC)
            "createdAt".nextYear(type, ZoneOffset.UTC)
        } as AndFilter

        expression.operands.assert().containsExactly(
            YesterdayFilter(field, ZoneOffset.UTC.id),
            NextMonthFilter(field, ZoneOffset.UTC.id),
            LastYearFilter(field, ZoneOffset.UTC.id),
            ThisYearFilter(field, ZoneOffset.UTC.id),
            NextYearFilter(field, ZoneOffset.UTC.id),
        )
    }

    @Test
    fun `should configure relative temporal type`() {
        val type = FieldType.Temporal.Number(TimeUnit.SECONDS)
        val expression = filter {
            "createdAt".beforeToday(LocalTime.NOON, type)
        }

        expression.assert().isEqualTo(
            BeforeTodayFilter(
                field = LogicalField("createdAt", type),
                time = "12:00",
            ),
        )
    }

    @Test
    fun `should build dedicated metadata filters`() {
        val expression = filter {
            id("id-1")
            ids("id-1", "id-2")
            aggregateId("aggregate-1")
            aggregateIds("aggregate-1", "aggregate-2")
            tenantId("tenant-1")
            ownerId("owner-1")
            spaceId("space-1")
        } as AndFilter

        expression.operands.map { it::class }.assert().containsExactly(
            IdFilter::class,
            IdsFilter::class,
            AggregateIdFilter::class,
            AggregateIdsFilter::class,
            TenantIdFilter::class,
            OwnerIdFilter::class,
            SpaceIdFilter::class,
        )
    }

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
    fun `should resolve scoped paths without duplicate prefixes`() {
        val expression = filter {
            "state".path {
                "state" eq "root"
                "state.name" eq "Wow"
                "statement" eq "value"
                "items".path {
                    "productId" eq "product-1"
                }
            }
            "tenantId" eq "tenant-1"
        } as AndFilter

        expression.operands.assert().hasSize(2)
        val state = expression.operands[0] as AndFilter
        state.operands.assert().hasSize(4)
        (state.operands[0] as EqualFilter).field.assert().isEqualTo(LogicalField("state.state"))
        (state.operands[1] as EqualFilter).field.assert().isEqualTo(LogicalField("state.name"))
        (state.operands[2] as EqualFilter).field.assert().isEqualTo(LogicalField("state.statement"))
        (state.operands[3] as EqualFilter).field.assert().isEqualTo(LogicalField("state.items.productId"))
        (expression.operands[1] as EqualFilter).field.assert().isEqualTo(LogicalField("tenantId"))
    }

    @Test
    fun `should keep path block as one OR operand`() {
        val expression = filter {
            or {
                "state".path {
                    "status" eq "CREATED"
                    "ownerId" eq "owner-1"
                }
                "tenantId" eq "tenant-1"
            }
        } as OrFilter

        expression.operands.assert().hasSize(2)
        (expression.operands[0] as AndFilter).operands.assert().hasSize(2)
        (expression.operands[1] as EqualFilter).field.assert().isEqualTo(LogicalField("tenantId"))
    }

    @Test
    fun `should reject injected expression inside path scope`() {
        val prebuilt = filter { "name" eq "Wow" }

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter {
                "state".path {
                    expression(prebuilt)
                }
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `should reject injected expression inside nested path scope`() {
        val prebuilt = filter { "productId" eq "product-1" }

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter {
                "state".path {
                    "items".nested {
                        expression(prebuilt)
                    }
                }
            }
        }
    }

    @Test
    fun `should reject invalid path scope`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter {
                "invalid path".path {
                    matchAll()
                }
            }
        }
    }

    @Test
    fun `should reject empty path block`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter {
                "state".path { }
            }
        }
    }

    @Test
    fun `should reject deletion inside path scope`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter {
                "state".path {
                    deletion(DeletionState.DELETED)
                }
            }
        }
    }

    @Test
    fun `should keep injected element fields relative`() {
        val itemFilter = filter { "productId" eq "product-1" }

        val expression = filter {
            "state.items".elementMatch {
                expression(itemFilter)
            }
        } as ElementMatchFilter

        expression.predicate.assert().isSameAs(itemFilter)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `should preserve deprecated nested source compatibility`() {
        val expression = filter {
            "state".nested {
                "name" eq "Wow"
            }
        }

        (expression as EqualFilter).field.assert().isEqualTo(LogicalField("state.name"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `should reject empty nested block`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter {
                "state".nested { }
            }
        }
    }

    @Test
    fun `should reject empty element match block`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter {
                "items".elementMatch { }
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `should preserve deprecated nested logical semantics`() {
        val expression = filter {
            or {
                "state".nested {
                    "status" eq "CREATED"
                    "ownerId" eq "owner-1"
                }
                "tenantId" eq "tenant-1"
            }
        } as OrFilter

        expression.operands.assert().hasSize(3)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `should preserve prebuilt expressions in deprecated nested logical blocks`() {
        val prebuilt = filter { "name" eq "Wow" }

        val expression = filter {
            "state".nested {
                or {
                    expression(prebuilt)
                    "status" eq "CREATED"
                }
            }
        } as OrFilter

        expression.operands[0].assert().isSameAs(prebuilt)
        (expression.operands[1] as EqualFilter).field.assert().isEqualTo(LogicalField("state.status"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `should preserve prebuilt expressions in nested chains from root`() {
        val prebuilt = filter { "name" eq "Wow" }

        val expression = filter {
            "state".nested {
                "child".nested {
                    expression(prebuilt)
                }
            }
        }

        expression.assert().isSameAs(prebuilt)
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
            pathState { "name" eq "Wow" }
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
            "containsDefault".contains("value")
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
            "today".today(FieldType.Temporal.String(datePattern = "yyyy-MM-dd"), ZoneOffset.UTC)
            "beforeToday".beforeToday(LocalTime.NOON, zoneId = ZoneOffset.UTC)
            "tomorrow".tomorrow()
            "thisWeek".thisWeek()
            "nextWeek".nextWeek()
            "lastWeek".lastWeek()
            "thisMonth".thisMonth()
            "lastMonth".lastMonth()
            "recentDays".recentDays(2)
            "earlierDays".earlierDays(2)
            "todayDefault".today()
            "beforeTodayDefault".beforeToday(LocalTime.NOON)
            "tomorrowUtc".tomorrow(zoneId = ZoneOffset.UTC)
            "thisWeekUtc".thisWeek(zoneId = ZoneOffset.UTC)
            "nextWeekUtc".nextWeek(zoneId = ZoneOffset.UTC)
            "lastWeekUtc".lastWeek(zoneId = ZoneOffset.UTC)
            "thisMonthUtc".thisMonth(zoneId = ZoneOffset.UTC)
            "lastMonthUtc".lastMonth(zoneId = ZoneOffset.UTC)
            "recentDaysUtc".recentDays(2, zoneId = ZoneOffset.UTC)
            "earlierDaysUtc".earlierDays(2, zoneId = ZoneOffset.UTC)
        } as AndFilter

        expression.operands.assert().hasSize(51)
        (expression.operands[6] as EqualFilter).field.assert().isEqualTo(LogicalField("state.name"))
    }
}
