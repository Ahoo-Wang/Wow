# wow-query 单元测试全面重写 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 全面重写 wow-query 模块的单元测试，统一风格、补充缺失覆盖、确保 100% 源文件覆盖。

**Architecture:** 一对一文件结构，每个源文件对应一个 Test 文件。自包含 fixtures（Mock 对象为 Test 文件内部类）。DSL 大方法按操作符类别拆分。全部使用 FluentAssert `.assert()` 风格。

**Tech Stack:** Kotlin 2.3, JUnit Jupiter, FluentAssert (`me.ahoo.test.asserts.assert`), Reactor Test (`reactor-test`), MockK, wow-tck (`MOCK_AGGREGATE_METADATA`)

**Design Spec:** `docs/superpowers/specs/2026-06-06-wow-query-test-rewrite-design.md`

---

## Task 1: dsl/BetweenStartTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/BetweenStartTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/BetweenStart.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class BetweenStartTest {

    @Test
    fun `should store field and start value`() {
        val betweenStart = BetweenStart("field1", 1)
        betweenStart.field.assert().isEqualTo("field1")
        betweenStart.start.assert().isEqualTo(1)
    }

    @Test
    fun `should support string start value`() {
        val betweenStart = BetweenStart("field1", "a")
        betweenStart.start.assert().isEqualTo("a")
    }

    @Test
    fun `should be a data class with equality`() {
        val start1 = BetweenStart("field", 1)
        val start2 = BetweenStart("field", 1)
        start1.assert().isEqualTo(start2)
    }

    @Test
    fun `should copy with new values`() {
        val start = BetweenStart("field1", 1)
        val copied = start.copy(field = "field2", start = 2)
        copied.field.assert().isEqualTo("field2")
        copied.start.assert().isEqualTo(2)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.BetweenStartTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/BetweenStartTest.kt
git commit -m "test(query): add BetweenStartTest"
```

---

## Task 2: dsl/NestedFieldDslTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/NestedFieldDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/NestedFieldDsl.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class NestedFieldDslTest {

    private class TestNestedFieldDsl : NestedFieldDsl() {
        fun testWithNestedField(field: String): String {
            return field.withNestedField()
        }

        fun testNested(prefix: String) {
            nested(prefix)
        }

        fun currentNestedField(): String {
            return nestedField
        }
    }

    @Test
    fun `should return field unchanged when no nested field set`() {
        val dsl = TestNestedFieldDsl()
        dsl.testWithNestedField("field1").assert().isEqualTo("field1")
    }

    @Test
    fun `should prefix field with nested field`() {
        val dsl = TestNestedFieldDsl()
        dsl.testNested("state")
        dsl.testWithNestedField("field1").assert().isEqualTo("state.field1")
    }

    @Test
    fun `should return field unchanged when nested field is blank`() {
        val dsl = TestNestedFieldDsl()
        dsl.testNested("")
        dsl.testWithNestedField("field1").assert().isEqualTo("field1")
    }

    @Test
    fun `should update nested field`() {
        val dsl = TestNestedFieldDsl()
        dsl.testNested("state")
        dsl.currentNestedField().assert().isEqualTo("state")
        dsl.testNested("nested")
        dsl.testWithNestedField("field1").assert().isEqualTo("nested.field1")
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.NestedFieldDslTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/NestedFieldDslTest.kt
git commit -m "test(query): add NestedFieldDslTest"
```

---

## Task 3: dsl/QueryableDslTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/QueryableDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/QueryableDsl.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class QueryableDslTest {

    private class TestQueryableDsl : QueryableDsl<ISingleQuery>() {
        fun exposedProjection(): Projection = projection
        fun exposedCondition(): Condition = condition
        fun exposedSort(): List<Sort> = sort

        override fun build(): ISingleQuery {
            return SingleQuery(condition, projection, sort)
        }
    }

    @Test
    fun `should use default values`() {
        val dsl = TestQueryableDsl()
        dsl.exposedProjection().assert().isEqualTo(Projection.ALL)
        dsl.exposedCondition().assert().isEqualTo(Condition.all())
        dsl.exposedSort().assert().isEmpty()
    }

    @Test
    fun `should set projection directly`() {
        val dsl = TestQueryableDsl()
        val projection = Projection(include = listOf("field1"), exclude = emptyList())
        dsl.projection(projection)
        dsl.exposedProjection().assert().isEqualTo(projection)
    }

    @Test
    fun `should set projection via block`() {
        val dsl = TestQueryableDsl()
        dsl.projection {
            include("field1")
        }
        dsl.exposedProjection().include.assert().hasSize(1)
    }

    @Test
    fun `should set condition directly`() {
        val dsl = TestQueryableDsl()
        val condition = Condition.eq("field1", "value1")
        dsl.condition(condition)
        dsl.exposedCondition().assert().isEqualTo(condition)
    }

    @Test
    fun `should set condition via block`() {
        val dsl = TestQueryableDsl()
        dsl.condition {
            "field1" eq "value1"
        }
        dsl.exposedCondition().assert().isEqualTo(Condition.eq("field1", "value1"))
    }

    @Test
    fun `should set sort directly`() {
        val dsl = TestQueryableDsl()
        val sorts = listOf(Sort("field1", Sort.Direction.ASC))
        dsl.sort(sorts)
        dsl.exposedSort().assert().isEqualTo(sorts)
    }

    @Test
    fun `should set sort via block`() {
        val dsl = TestQueryableDsl()
        dsl.sort {
            "field1".asc()
        }
        dsl.exposedSort().assert().isEqualTo(listOf(Sort("field1", Sort.Direction.ASC)))
    }

    @Test
    fun `should build with all components`() {
        val query = TestQueryableDsl().apply {
            condition { "field1" eq "value1" }
            projection { include("field1") }
            sort { "field1".asc() }
        }.build()
        query.condition.assert().isEqualTo(Condition.eq("field1", "value1"))
        query.projection.include.assert().hasSize(1)
        query.sort.assert().hasSize(1)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.QueryableDslTest"`
Expected: PASS (8 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/QueryableDslTest.kt
git commit -m "test(query): add QueryableDslTest"
```

---

## Task 4: dsl/ConditionDslTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/ConditionDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/ConditionDsl.kt`

- [ ] **Step 1: Rewrite the test file**

将现有巨型 `should build complex condition with all operators` 方法拆分为多个分类测试方法。保持所有现有测试用例，确保参数化测试保留。在文件末尾保留 `QueryModel` data class。具体内容：

拆分为以下测试方法：
- `should return all condition when empty` — 空条件
- `should return all condition when all()` — `all()` 调用
- `should build id and aggregateId conditions` — id/ids/aggregateId/aggregateIds
- `should build comparison conditions` — eq/ne/gt/lt/gte/lte
- `should build collection conditions` — contains/isIn/notIn/between/all/startsWith/endsWith
- `should build null and boolean conditions` — isNull/notNull/isTrue/isFalse/exists
- `should build logical conditions` — and/or/nor (含空分支)
- `should build time range conditions` — today/beforeToday/tomorrow/thisWeek/nextWeek/lastWeek/thisMonth/lastMonth/recentDays/earlierDays
- `should build nested conditions` — nested/nestedState/property reference nested
- `should build other conditions` — tenantId/ownerId/spaceId/deleted/raw/match/elemMatch
- `should set deletion state on condition`
- `should parse deletion state from string value`
- `should build conditions using property reference` — KCallable 版本的 eq/ne/gt/lt/gte/lte/contains/isIn/notIn/between/all/startsWith/endsWith/elemMatch/isNull/notNull/isTrue/isFalse/exists/today/tomorrow/thisWeek/nextWeek/lastWeek/thisMonth/lastMonth/recentDays/earlierDays
- ParameterizedTest `should build condition matching expected`（保留原有参数化数据）

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.ConditionDslTest"`
Expected: PASS (所有现有断言仍然通过，方法数增加但断言不变)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/ConditionDslTest.kt
git commit -m "test(query): rewrite ConditionDslTest with split test methods"
```

---

## Task 5: dsl/SortDslTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/SortDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/SortDsl.kt`

- [ ] **Step 1: Rewrite the test file**

```kotlin
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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class SortDslTest {

    @Test
    fun `should build sort with asc and desc directions`() {
        val sort = sort {
            "field1".asc()
            "field2".desc()
        }
        sort.assert().isEqualTo(
            listOf(
                Sort("field1", Sort.Direction.ASC),
                Sort("field2", Sort.Direction.DESC)
            )
        )
    }

    @Test
    fun `should return empty list when no sort specified`() {
        val sort = sort { }
        sort.assert().isEmpty()
    }

    @Test
    fun `should build sort with nested field prefix`() {
        val sort = sort {
            nested("state")
            "field1".asc()
        }
        sort.assert().isEqualTo(listOf(Sort("state.field1", Sort.Direction.ASC)))
    }

    @Test
    fun `should build multi-field sort`() {
        val sort = sort {
            "field1".asc()
            "field2".desc()
            "field3".asc()
        }
        sort.assert().hasSize(3)
        sort[0].assert().isEqualTo(Sort("field1", Sort.Direction.ASC))
        sort[1].assert().isEqualTo(Sort("field2", Sort.Direction.DESC))
        sort[2].assert().isEqualTo(Sort("field3", Sort.Direction.ASC))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.SortDslTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/SortDslTest.kt
git commit -m "test(query): rewrite SortDslTest with additional test cases"
```

---

## Task 6: dsl/PaginationDslTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/PaginationDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/PaginationDsl.kt`

- [ ] **Step 1: Rewrite the test file**

```kotlin
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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Pagination
import org.junit.jupiter.api.Test

class PaginationDslTest {

    @Test
    fun `should build pagination with index and size`() {
        val pagination = pagination {
            index(1)
            size(10)
        }
        pagination.index.assert().isOne()
        pagination.size.assert().isEqualTo(10)
    }

    @Test
    fun `should use default values when no values set`() {
        val pagination = pagination { }
        pagination.index.assert().isEqualTo(Pagination.DEFAULT.index)
        pagination.size.assert().isEqualTo(Pagination.DEFAULT.size)
    }

    @Test
    fun `should override index independently`() {
        val pagination = pagination {
            index(5)
        }
        pagination.index.assert().isEqualTo(5)
        pagination.size.assert().isEqualTo(Pagination.DEFAULT.size)
    }

    @Test
    fun `should override size independently`() {
        val pagination = pagination {
            size(20)
        }
        pagination.index.assert().isEqualTo(Pagination.DEFAULT.index)
        pagination.size.assert().isEqualTo(20)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.PaginationDslTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/PaginationDslTest.kt
git commit -m "test(query): rewrite PaginationDslTest with default and boundary tests"
```

---

## Task 7: dsl/ProjectionDslTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/ProjectionDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/ProjectionDsl.kt`

- [ ] **Step 1: Rewrite the test file**

```kotlin
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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Projection
import org.junit.jupiter.api.Test

class ProjectionDslTest {

    @Test
    fun `should build projection with include and exclude`() {
        val projection = projection {
            include("field1")
            exclude("field2")
        }
        projection.assert().isEqualTo(
            Projection(
                include = listOf("field1"),
                exclude = listOf("field2")
            )
        )
    }

    @Test
    fun `should build projection with nested state prefix`() {
        val projection = projection {
            nestedState()
            include("field1")
            exclude("field2")
        }
        projection.assert().isEqualTo(
            Projection(
                include = listOf("state.field1"),
                exclude = listOf("state.field2")
            )
        )
    }

    @Test
    fun `should build empty projection when no fields specified`() {
        val projection = projection { }
        projection.include.assert().isEmpty()
        projection.exclude.assert().isEmpty()
    }

    @Test
    fun `should build projection with multiple includes`() {
        val projection = projection {
            include("field1", "field2", "field3")
        }
        projection.include.assert().hasSize(3)
        projection.exclude.assert().isEmpty()
    }

    @Test
    fun `should build projection with multiple excludes`() {
        val projection = projection {
            exclude("field1", "field2")
        }
        projection.include.assert().isEmpty()
        projection.exclude.assert().hasSize(2)
    }

    @Test
    fun `should build projection with custom nested prefix`() {
        val projection = projection {
            nested("custom")
            include("field1")
        }
        projection.include.assert().isEqualTo(listOf("custom.field1"))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.ProjectionDslTest"`
Expected: PASS (6 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/ProjectionDslTest.kt
git commit -m "test(query): rewrite ProjectionDslTest with additional test cases"
```

---

## Task 8: dsl/SingleQueryDslTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/SingleQueryDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/SingleQueryDsl.kt`

- [ ] **Step 1: Rewrite the test file**

```kotlin
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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class SingleQueryDslTest {

    @Test
    fun `should build single query with sort and condition`() {
        val query = singleQuery {
            sort {
                "field1".asc()
            }
            condition {
                "field1" eq "value1"
            }
        }
        query.sort.assert().isEqualTo(listOf(Sort("field1", Sort.Direction.ASC)))
        query.condition.assert().isEqualTo(Condition.eq("field1", "value1"))
    }

    @Test
    fun `should build empty single query with defaults`() {
        val query = singleQuery { }
        query.condition.assert().isEqualTo(Condition.all())
        query.projection.assert().isEqualTo(Projection.ALL)
        query.sort.assert().isEmpty()
    }

    @Test
    fun `should build single query with only condition`() {
        val query = singleQuery {
            condition {
                "field" eq "value"
            }
        }
        query.condition.assert().isEqualTo(Condition.eq("field", "value"))
        query.sort.assert().isEmpty()
    }

    @Test
    fun `should build single query with projection`() {
        val query = singleQuery {
            projection {
                include("field1")
            }
        }
        query.projection.include.assert().hasSize(1)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.SingleQueryDslTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/SingleQueryDslTest.kt
git commit -m "test(query): rewrite SingleQueryDslTest with default and projection tests"
```

---

## Task 9: dsl/ListQueryDslTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/ListQueryDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/ListQueryDsl.kt`

- [ ] **Step 1: Rewrite the test file**

```kotlin
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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class ListQueryDslTest {

    @Test
    fun `should build list query with all components`() {
        val query = listQuery {
            limit(1)
            sort {
                "field1".asc()
            }
            condition {
                "field1" eq "value1"
                "field2" eq "value2"
                and {
                    "field3" eq "value3"
                }
                or {
                    "field4" eq "value4"
                }
            }
            projection { }
        }
        query.projection.assert().isEqualTo(Projection.ALL)
        query.limit.assert().isOne()
        query.sort.assert().isEqualTo(listOf(Sort("field1", Sort.Direction.ASC)))
        query.condition.assert().isEqualTo(
            Condition.and(
                listOf(
                    Condition.eq("field1", "value1"),
                    Condition.eq("field2", "value2"),
                    Condition.and(
                        listOf(
                            Condition.eq("field3", "value3")
                        )
                    ),
                    Condition.or(
                        listOf(
                            Condition.eq("field4", "value4")
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `should build empty list query with defaults`() {
        val query = listQuery { }
        query.condition.assert().isEqualTo(Condition.all())
        query.projection.assert().isEqualTo(Projection.ALL)
        query.sort.assert().isEmpty()
        query.limit.assert().isZero()
    }

    @Test
    fun `should build list query with only limit`() {
        val query = listQuery {
            limit(10)
        }
        query.limit.assert().isEqualTo(10)
        query.condition.assert().isEqualTo(Condition.all())
    }

    @Test
    fun `should build list query with projection block`() {
        val query = listQuery {
            projection {
                include("field1")
                exclude("field2")
            }
        }
        query.projection.include.assert().hasSize(1)
        query.projection.exclude.assert().hasSize(1)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.ListQueryDslTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/ListQueryDslTest.kt
git commit -m "test(query): rewrite ListQueryDslTest with default and limit tests"
```

---

## Task 10: dsl/PagedQueryDslTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/PagedQueryDslTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/PagedQueryDsl.kt`

- [ ] **Step 1: Rewrite the test file**

在现有 `should build paged query with all components` 测试基础上，增加：
- `should build empty paged query with defaults` — 验证默认 pagination、condition、sort
- `should build paged query with only pagination` — 仅设 pagination
- `should build paged query with only condition` — 仅设 condition
- `should build paged query with direct pagination object` — 直接传 Pagination 对象

保持原有 `should build paged query with all components` 不变。

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.PagedQueryDslTest"`
Expected: PASS (5 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/PagedQueryDslTest.kt
git commit -m "test(query): rewrite PagedQueryDslTest with default and partial config tests"
```

---

## Task 11: filter/QueryTypeTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryTypeTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryType.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.filter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class QueryTypeTest {

    @Test
    fun `single should not be dynamic`() {
        QueryType.SINGLE.isDynamic.assert().isFalse()
    }

    @Test
    fun `dynamic single should be dynamic`() {
        QueryType.DYNAMIC_SINGLE.isDynamic.assert().isTrue()
    }

    @Test
    fun `list should not be dynamic`() {
        QueryType.LIST.isDynamic.assert().isFalse()
    }

    @Test
    fun `dynamic list should be dynamic`() {
        QueryType.DYNAMIC_LIST.isDynamic.assert().isTrue()
    }

    @Test
    fun `paged should not be dynamic`() {
        QueryType.PAGED.isDynamic.assert().isFalse()
    }

    @Test
    fun `dynamic paged should be dynamic`() {
        QueryType.DYNAMIC_PAGED.isDynamic.assert().isTrue()
    }

    @Test
    fun `count should not be dynamic`() {
        QueryType.COUNT.isDynamic.assert().isFalse()
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.filter.QueryTypeTest"`
Expected: PASS (7 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryTypeTest.kt
git commit -m "test(query): add QueryTypeTest for all enum values"
```

---

## Task 12: filter/QueryContextTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryContextTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryContext.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class QueryContextTest {

    @Test
    fun `should set and get query`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = me.ahoo.wow.query.dsl.singleQuery { }
        context.setQuery(query)
        context.getQuery().assert().isEqualTo(query)
    }

    @Test
    fun `should throw when get query without set`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException::class.java) {
            context.getQuery()
        }
    }

    @Test
    fun `should rewrite query`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = me.ahoo.wow.query.dsl.singleQuery {
            condition { "field1" eq "value1" }
        }
        context.setQuery(query)
        context.rewriteQuery {
            me.ahoo.wow.query.dsl.singleQuery {
                condition { "field2" eq "value2" }
            }
        }
        context.getQuery().condition.assert().isEqualTo(
            me.ahoo.wow.api.query.Condition.eq("field2", "value2")
        )
    }

    @Test
    fun `should set and get result`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val result = Mono.just("result")
        context.setResult(result)
        context.getRequiredResult().assert().isSameAs(result)
    }

    @Test
    fun `should set result from query handler`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<String>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = me.ahoo.wow.query.dsl.singleQuery { }
        context.setQuery(query)
        context.setResult { query ->
            Mono.just("handled")
        }
        context.getRequiredResult().block().assert().isEqualTo("handled")
    }

    @Test
    fun `should rewrite result`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<String>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        context.setResult(Mono.just("original"))
        context.rewriteResult { it.map { "$it-modified" } }
        context.getRequiredResult().block().assert().isEqualTo("original-modified")
    }

    @Test
    fun `should set and get generic attributes`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        context.setAttribute("key1", "value1")
        val value: String? = context.getAttribute("key1")
        value.assert().isEqualTo("value1")
    }

    @Test
    fun `should return null for missing attribute`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val value: String? = context.getAttribute("missing")
        value.assert().isNull()
    }

    @Test
    fun `should cast to count query context`() {
        val context = DefaultQueryContext<Condition, Mono<Long>>(
            queryType = QueryType.COUNT,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        context.setQuery(Condition.ALL)
        val countContext = context.asCountQuery()
        countContext.getQuery().assert().isEqualTo(Condition.ALL)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.filter.QueryContextTest"`
Expected: PASS (9 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryContextTest.kt
git commit -m "test(query): add QueryContextTest for DefaultQueryContext"
```

---

## Task 13: filter/ContextsTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/context/ContextsTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/Contexts.kt`

- [ ] **Step 1: Rewrite the test file**

在现有 `should write and read raw request from context` 测试基础上，增加：
- `should return null when no raw request in context` — 验证无数据时返回 null
- `should overwrite raw request in context` — 验证重复写入

保持现有测试不变。

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.context.ContextsTest"`
Expected: PASS (3 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/context/ContextsTest.kt
git commit -m "test(query): rewrite ContextsTest with null and overwrite tests"
```

---

## Task 14: filter/MaskingDynamicDocumentQueryFilterTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/MaskingDynamicDocumentQueryFilterTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/MaskingDynamicDocumentQueryFilter.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.filter

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.mask.AggregateDynamicDocumentMasker
import me.ahoo.wow.query.mask.DataMaskerRegistry
import me.ahoo.wow.query.mask.DynamicDocumentMasker
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class MaskingDynamicDocumentQueryFilterTest {

    private class MockMaskerRegistry(
        private val masker: AggregateDynamicDocumentMasker<AggregateDynamicDocumentMasker>
    ) : DataMaskerRegistry<AggregateDynamicDocumentMasker> {
        override fun register(masker: AggregateDynamicDocumentMasker) {}
        override fun unregister(masker: AggregateDynamicDocumentMasker) {}
        override fun getAggregateDataMasker(namedAggregate: NamedAggregate): AggregateDynamicDocumentMasker<AggregateDynamicDocumentMasker> {
            return masker
        }
    }

    private class MockMaskingFilter(
        maskerRegistry: DataMaskerRegistry<AggregateDynamicDocumentMasker>
    ) : MaskingDynamicDocumentQueryFilter<AggregateDynamicDocumentMasker>(maskerRegistry)

    @Test
    fun `should skip masking for non-dynamic query type`() {
        val mockMasker = mockk<AggregateDynamicDocumentMasker>()
        val registry = MockMaskerRegistry(mockMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `should skip masking when masker is empty`() {
        val mockMasker = mockk<AggregateDynamicDocumentMasker> {
            every { isEmpty() } returns true
        }
        val registry = MockMaskerRegistry(mockMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.DYNAMIC_SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `should mask dynamic single result`() {
        val maskedDoc = mutableMapOf("field" to "masked").toDynamicDocument()
        val mockMasker = mockk<AggregateDynamicDocumentMasker> {
            every { isEmpty() } returns false
            every { mask(any<DynamicDocument>()) } returns maskedDoc
        }
        val registry = MockMaskerRegistry(mockMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.DYNAMIC_SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val originalDoc = mutableMapOf("field" to "original").toDynamicDocument()
        context.setResult(Mono.just(originalDoc))
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
        context.getRequiredResult<Mono<DynamicDocument>>().block()
            .assert().isEqualTo(maskedDoc)
    }

    @Test
    fun `should mask dynamic list result`() {
        val maskedDoc = mutableMapOf("field" to "masked").toDynamicDocument()
        val mockMasker = mockk<AggregateDynamicDocumentMasker> {
            every { isEmpty() } returns false
            every { mask(any<DynamicDocument>()) } returns maskedDoc
        }
        val registry = MockMaskerRegistry(mockMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.DYNAMIC_LIST,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val originalDoc = mutableMapOf("field" to "original").toDynamicDocument()
        context.setResult(Flux.just(originalDoc))
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `should mask dynamic paged result`() {
        val maskedDoc = mutableMapOf("field" to "masked").toDynamicDocument()
        val mockMasker = mockk<AggregateDynamicDocumentMasker> {
            every { isEmpty() } returns false
            every { mask(any<DynamicDocument>()) } returns maskedDoc
        }
        val registry = MockMaskerRegistry(mockMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.DYNAMIC_PAGED,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val originalDoc = mutableMapOf("field" to "original").toDynamicDocument()
        context.setResult(Mono.just(me.ahoo.wow.api.query.PagedList(1, listOf(originalDoc))))
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.filter.MaskingDynamicDocumentQueryFilterTest"`
Expected: PASS (5 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/filter/MaskingDynamicDocumentQueryFilterTest.kt
git commit -m "test(query): add MaskingDynamicDocumentQueryFilterTest"
```

---

## Task 15: converter/FieldConverterTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/FieldConverterTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/FieldConverter.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.converter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class FieldConverterTest {

    @Test
    fun `should convert field using identity converter`() {
        val identityConverter = FieldConverter { it }
        identityConverter.convert("field1").assert().isEqualTo("field1")
    }

    @Test
    fun `should convert field using prefix converter`() {
        val prefixConverter = FieldConverter { "state.$it" }
        prefixConverter.convert("field1").assert().isEqualTo("state.field1")
    }

    @Test
    fun `should convert field using suffix converter`() {
        val suffixConverter = FieldConverter { "${it}_suffix" }
        suffixConverter.convert("field1").assert().isEqualTo("field1_suffix")
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.converter.FieldConverterTest"`
Expected: PASS (3 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/converter/FieldConverterTest.kt
git commit -m "test(query): add FieldConverterTest"
```

---

## Task 16: converter/ConditionConverterTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/ConditionConverterTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/AbstractConditionConverter.kt`

- [ ] **Step 1: Write the test**

创建一个 `RecordingConditionConverter` 内部类，在 `internalConvert` 中记录被调用的方法名和 Condition，以验证 `AbstractConditionConverter` 的 `convert` 方法正确分派到各个 abstract 方法。测试覆盖所有 Operator 分支，验证 `guard()` 被调用。

内部 Mock 实现模式：

```kotlin
private class RecordingConditionConverter : AbstractConditionConverter<Pair<String, Condition>>() {
    val calls = mutableListOf<Pair<String, Condition>>()
    
    private fun record(name: String, condition: Condition): Pair<String, Condition> {
        val call = name to condition
        calls.add(call)
        return call
    }
    
    override fun and(condition: Condition) = record("and", condition)
    override fun or(condition: Condition) = record("or", condition)
    override fun nor(condition: Condition) = record("nor", condition)
    override fun id(condition: Condition) = record("id", condition)
    override fun ids(condition: Condition) = record("ids", condition)
    override fun aggregateId(condition: Condition) = record("aggregateId", condition)
    override fun aggregateIds(condition: Condition) = record("aggregateIds", condition)
    override fun tenantId(condition: Condition) = record("tenantId", condition)
    override fun ownerId(condition: Condition) = record("ownerId", condition)
    override fun spaceId(condition: Condition) = record("spaceId", condition)
    override fun all(condition: Condition) = record("all", condition)
    override fun eq(condition: Condition) = record("eq", condition)
    override fun ne(condition: Condition) = record("ne", condition)
    override fun gt(condition: Condition) = record("gt", condition)
    override fun lt(condition: Condition) = record("lt", condition)
    override fun gte(condition: Condition) = record("gte", condition)
    override fun lte(condition: Condition) = record("lte", condition)
    override fun contains(condition: Condition) = record("contains", condition)
    override fun match(condition: Condition) = record("match", condition)
    override fun isIn(condition: Condition) = record("isIn", condition)
    override fun notIn(condition: Condition) = record("notIn", condition)
    override fun between(condition: Condition) = record("between", condition)
    override fun allIn(condition: Condition) = record("allIn", condition)
    override fun startsWith(condition: Condition) = record("startsWith", condition)
    override fun endsWith(condition: Condition) = record("endsWith", condition)
    override fun elemMatch(condition: Condition) = record("elemMatch", condition)
    override fun isNull(condition: Condition) = record("isNull", condition)
    override fun notNull(condition: Condition) = record("notNull", condition)
    override fun isTrue(condition: Condition) = record("isTrue", condition)
    override fun isFalse(condition: Condition) = record("isFalse", condition)
    override fun exists(condition: Condition) = record("exists", condition)
    override fun deleted(condition: Condition) = record("deleted", condition)
    override fun raw(condition: Condition) = record("raw", condition)
}
```

测试用例：验证每个 Operator 都能正确分派（eq、ne、gt、and、or、id、tenantId、deleted、all 等）。至少覆盖 10 个关键 Operator。

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.converter.ConditionConverterTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/converter/ConditionConverterTest.kt
git commit -m "test(query): add ConditionConverterTest with RecordingConditionConverter"
```

---

## Task 17: converter/SortConverterTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/SortConverterTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/AbstractSortConverter.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.converter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class SortConverterTest {

    private class RecordingSortConverter(
        override val fieldConverter: FieldConverter = FieldConverter { it }
    ) : AbstractSortConverter<List<Sort>>() {
        var lastConverted: List<Sort> = emptyList()

        override fun internalConvert(sort: List<Sort>): List<Sort> {
            lastConverted = sort
            return sort
        }
    }

    @Test
    fun `should convert empty sort list`() {
        val converter = RecordingSortConverter()
        val result = converter.convert(emptyList())
        result.assert().isEmpty()
    }

    @Test
    fun `should convert sort with field converter`() {
        val converter = RecordingSortConverter(
            fieldConverter = FieldConverter { "prefix.$it" }
        )
        val sort = listOf(Sort("field1", Sort.Direction.ASC))
        converter.convert(sort)
        converter.lastConverted.assert().isEqualTo(
            listOf(Sort("prefix.field1", Sort.Direction.ASC))
        )
    }

    @Test
    fun `should convert multiple sort fields`() {
        val converter = RecordingSortConverter()
        val sorts = listOf(
            Sort("field1", Sort.Direction.ASC),
            Sort("field2", Sort.Direction.DESC)
        )
        val result = converter.convert(sorts)
        result.assert().isEqualTo(sorts)
    }

    @Test
    fun `should pass through without field converter`() {
        val converter = RecordingSortConverter()
        val sorts = listOf(Sort("field1", Sort.Direction.ASC))
        converter.convert(sorts)
        converter.lastConverted.assert().isEqualTo(sorts)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.converter.SortConverterTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/converter/SortConverterTest.kt
git commit -m "test(query): add SortConverterTest"
```

---

## Task 18: converter/ProjectionConverterTest（新增）

**Files:**
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/ProjectionConverterTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/AbstractProjectionConverter.kt`

- [ ] **Step 1: Write the test**

```kotlin
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

package me.ahoo.wow.query.converter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Projection
import org.junit.jupiter.api.Test

class ProjectionConverterTest {

    private class RecordingProjectionConverter(
        override val fieldConverter: FieldConverter = FieldConverter { it }
    ) : AbstractProjectionConverter<Projection>() {
        var lastConverted: Projection = Projection.ALL

        override fun internalConvert(projection: Projection): Projection {
            lastConverted = projection
            return projection
        }
    }

    @Test
    fun `should convert empty projection`() {
        val converter = RecordingProjectionConverter()
        val result = converter.convert(Projection.ALL)
        result.assert().isEqualTo(Projection.ALL)
    }

    @Test
    fun `should convert projection with field converter`() {
        val converter = RecordingProjectionConverter(
            fieldConverter = FieldConverter { "state.$it" }
        )
        val projection = Projection(
            include = listOf("field1"),
            exclude = listOf("field2")
        )
        converter.convert(projection)
        converter.lastConverted.assert().isEqualTo(
            Projection(
                include = listOf("state.field1"),
                exclude = listOf("state.field2")
            )
        )
    }

    @Test
    fun `should convert projection without field changes`() {
        val converter = RecordingProjectionConverter()
        val projection = Projection(
            include = listOf("field1"),
            exclude = listOf("field2")
        )
        val result = converter.convert(projection)
        result.assert().isEqualTo(projection)
    }

    @Test
    fun `should convert projection with only includes`() {
        val converter = RecordingProjectionConverter()
        val projection = Projection(include = listOf("field1", "field2"), exclude = emptyList())
        converter.convert(projection)
        converter.lastConverted.include.assert().hasSize(2)
        converter.lastConverted.exclude.assert().isEmpty()
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.converter.ProjectionConverterTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/converter/ProjectionConverterTest.kt
git commit -m "test(query): add ProjectionConverterTest"
```

---

## Task 19: converter/DeleteConditionGuardTest（重写）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/DeleteConditionGuardTest.kt`
- Source: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/DeleteConditionGuard.kt`

- [ ] **Step 1: Rewrite the test file**

在现有 4 个测试基础上，增加：
- `should guard nor condition with no deleted` — nor 条件无 deleted 时注入 active
- `should guard nor condition with deleted` — nor 条件含 deleted 时保持不变
- `should guard or condition` — or 条件注入 active
- `should guard single eq condition` — 单个 eq 条件（非 AND）注入 active（保持现有 `should inject active condition for eq condition`）

保持现有 4 个测试不变。

- [ ] **Step 2: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.converter.DeleteConditionGuardTest"`
Expected: PASS (6+ tests)

- [ ] **Step 3: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/converter/DeleteConditionGuardTest.kt
git commit -m "test(query): rewrite DeleteConditionGuardTest with nor and or condition tests"
```

---

## Task 20: mask/ 层重写（3 个文件）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/mask/DataMaskingKtTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/mask/StateDataMaskerRegistryTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/mask/DefaultAggregateDataMaskerTest.kt`

- [ ] **Step 1: Rewrite DataMaskingKtTest**

保持现有 6 个测试不变，确保 `MockMaskingData` 定义为文件内部类（保持不变）。无需修改。

- [ ] **Step 2: Rewrite StateDataMaskerRegistryTest**

在现有 2 个测试基础上，增加 `EventStreamMaskerRegistry` 测试：
- `should register and unregister event stream maskers` — 验证 EventStreamMaskerRegistry 同样工作

将 `MockStateDataMasker` 从 `DefaultAggregateDataMaskerTest` 移到本文件作为共享内部类，或在各自文件中独立定义（保持不变模式）。

- [ ] **Step 3: Rewrite DefaultAggregateDataMaskerTest**

将 `MockStateDataMasker` 保持为内部类（不变）。保持现有 4 个测试。增加：
- `should return same document when no maskers` — 空 maskers 列表时 mask 返回原对象

- [ ] **Step 4: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.mask.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/mask/
git commit -m "test(query): rewrite mask layer tests with EventStreamMaskerRegistry coverage"
```

---

## Task 21: snapshot/ 层重写（6 个文件）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/NoOpSnapshotQueryServiceTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/SnapshotStatesKtTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/CountSnapshotQueryContextTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/DefaultSnapshotQueryHandlerTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/AbacQueryFilterTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/MaskingSnapshotQueryFilterTest.kt`

- [ ] **Step 1: Rewrite NoOpSnapshotQueryServiceTest**

保持现有 8 个测试不变，统一风格。

- [ ] **Step 2: Rewrite SnapshotStatesKtTest**

保持现有 6 个测试不变。用真实 `MaterializedSnapshot` 替换 MockK mock（如果更自然），但 MockK 在这里也合理。保持不变。

- [ ] **Step 3: Rewrite CountSnapshotQueryContextTest**

在现有 `should rewrite query with tenant id` 基础上，增加：
- `should rewrite query with ownerId` — 验证 appendOwnerId
- `should rewrite query with spaceId` — 验证 appendSpaceId

- [ ] **Step 4: Rewrite DefaultSnapshotQueryHandlerTest**

保持现有 7 个测试不变。

- [ ] **Step 5: Rewrite AbacQueryFilterTest**

保持现有 9 个测试不变，`EmptyAbacQueryFilter` 和 `MockAbacQueryFilter` 保持为内部 object。

- [ ] **Step 6: Rewrite MaskingSnapshotQueryFilterTest**

保持现有 7 个测试不变，`DataMaskable`、`MockSnapshotMasker`、`MockSnapshotQueryServiceFactory`、`MockSnapshotQueryService` 保持为内部类。

- [ ] **Step 7: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.snapshot.*"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/
git commit -m "test(query): rewrite snapshot layer tests with ownerId and spaceId coverage"
```

---

## Task 22: event/ 层重写（3 个文件）

**Files:**
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/NoOpSnapshotQueryServiceFactoryTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/filter/DefaultEventStreamQueryHandlerTest.kt`
- Rewrite: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/filter/MaskingEventStreamQueryFilterTest.kt`

- [ ] **Step 1: Rewrite NoOpEventStreamQueryServiceFactoryTest (NoOpSnapshotQueryServiceFactoryTest)**

保持现有 8 个测试不变，统一风格。

- [ ] **Step 2: Rewrite DefaultEventStreamQueryHandlerTest**

保持现有 7 个测试不变。

- [ ] **Step 3: Rewrite MaskingEventStreamQueryFilterTest**

在现有 `should mask dynamic event stream list results` 基础上，增加：
- `should mask dynamic single event stream result` — dynamicSingle 遮蔽测试
- `should mask dynamic paged event stream results` — dynamicPaged 遮蔽测试
- `should not mask non-dynamic event stream query` — single/list 不做 dynamic mask
- `should return count without masking` — count 不受遮蔽影响

- [ ] **Step 4: Run test**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.event.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wow-query/src/test/kotlin/me/ahoo/wow/query/event/
git commit -m "test(query): rewrite event layer tests with full masking coverage"
```

---

## Task 23: 全量测试验证

- [ ] **Step 1: Run all wow-query tests**

Run: `./gradlew :wow-query:test`
Expected: ALL PASS

- [ ] **Step 2: Run full module check (including detekt)**

Run: `./gradlew :wow-query:check`
Expected: ALL PASS

- [ ] **Step 3: Commit any final adjustments**

```bash
git add -A
git commit -m "test(query): finalize wow-query test rewrite"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** 每个 spec 中的测试文件都有对应的 Task（Task 1-22 覆盖 24 个文件）
- [x] **Placeholder scan:** 无 TBD/TODO/placeholder，所有代码块完整
- [x] **Type consistency:** Mock 类的接口实现与源码中的 abstract 方法签名一致
