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

/**
 * Specialized DSL marker annotation for Wow query domain-specific operations.
 *
 * This annotation provides fine-grained control over DSL contexts within the Wow query framework,
 * specifically targeting query-related domain operations such as filtering, projection, and aggregation.
 * It extends the base `@DslMarker` functionality to create isolated scopes for different query
 * construction phases, ensuring type-safe and context-aware query building.
 *
 * ## Purpose
 *
 * The `@QueryDslMarker` annotation serves as a specialized marker for query DSL components that need
 * stricter isolation than the general `@QueryDsl` marker. It is particularly useful for:
 * - Complex query composition with multiple nested contexts
 * - Domain-specific query operations (filtering, joining, aggregation)
 * - Preventing method leakage between closely related but distinct DSL phases
 * - Maintaining clear boundaries in advanced query scenarios
 *
 * ## Scope and Application
 *
 * Apply this marker to DSL interfaces and classes that handle:
 * - **Filter DSLs**: Where clauses, condition building, predicate composition
 * - **Projection DSLs**: Select clauses, field specification, result shaping
 * - **Aggregation DSLs**: Group by operations, aggregate functions, statistical queries
 * - **Join DSLs**: Relationship traversal, multi-entity queries
 *
 * ## How It Works
 *
 * `@QueryDslMarker` leverages Kotlin's `@DslMarker` mechanism to restrict implicit `this` references
 * in lambda expressions. When applied to DSL classes, it prevents methods from outer contexts
 * from being implicitly accessible, creating clean separation between different query operations.
 *
 * ## Usage Examples
 *
 * ### Filter DSL with Strict Isolation
 * ```kotlin
 * @QueryDslMarker
 * interface FilterDsl {
 *     fun where(init: ConditionDsl.() -> Unit): FilterDsl
 *     fun having(init: AggregateConditionDsl.() -> Unit): FilterDsl
 * }
 *
 * @QueryDslMarker
 * interface ConditionDsl {
 *     infix fun String.eq(value: Any): Condition
 *     infix fun String.gt(value: Any): Condition
 *     // Methods from FilterDsl not accessible here
 * }
 * ```
 *
 * ### Projection DSL
 * ```kotlin
 * @QueryDslMarker
 * interface ProjectionDsl {
 *     fun select(vararg fields: String): ProjectionDsl
 *     fun exclude(vararg fields: String): ProjectionDsl
 *     fun include(init: FieldSelectorDsl.() -> Unit): ProjectionDsl
 * }
 *
 * @QueryDslMarker
 * interface FieldSelectorDsl {
 *     fun field(name: String): FieldSelector
 *     fun nested(path: String, init: FieldSelectorDsl.() -> Unit): FieldSelector
 *     // ProjectionDsl methods not accessible here
 * }
 * ```
 *
 * ### Query Composition Example
 * ```kotlin
 * val complexQuery = query {
 *     filter {                          // FilterDsl context
 *         where {                        // ConditionDsl context
 *             "status" eq "active"        // ConditionDsl methods only
 *             "age" gt 18
 *             // filter.where() not accessible due to @QueryDslMarker
 *         }
 *         having {                       // AggregateConditionDsl context
 *             count("orders") gt 5       // Aggregate methods only
 *             // filter.having() not accessible
 *         }
 *     }
 *     project {                         // ProjectionDsl context
 *         select("name", "email")        // ProjectionDsl methods only
 *         include {                      // FieldSelectorDsl context
 *             field("profile")
 *             // project.select() not accessible
 *         }
 *     }
 * }
 * ```
 *
 * ## Benefits
 *
 * - **Enhanced Type Safety**: Stricter isolation prevents context confusion
 * - **Domain Clarity**: Clear separation of query operation concerns
 * - **Composability**: Safe nesting of related but distinct DSL contexts
 * - **Refactoring Safety**: Changes to one DSL context don't affect others
 * - **IDE Experience**: Precise code completion and error detection
 *
 * ## Implementation Guidelines
 *
 * - Apply to DSL interfaces handling specific query domains
 * - Use in conjunction with `@QueryDsl` for layered isolation
 * - Consider the granularity of DSL contexts when applying
 * - Test DSL composition to ensure proper isolation
 *
 * ## Relationship to QueryDsl
 *
 * While `@QueryDsl` provides general DSL isolation, `@QueryDslMarker` offers specialized
 * isolation for complex query scenarios requiring multiple nested contexts. Use `@QueryDsl`
 * for basic query construction and `@QueryDslMarker` for advanced domain-specific operations.
 *
 * @see QueryDsl for general query DSL isolation
 * @see DslMarker for the underlying Kotlin compiler support
 * @see me.ahoo.wow.query.dsl.condition for condition DSL implementations
 * @see me.ahoo.wow.query.dsl.projection for projection DSL implementations
 * @since 1.0.0
 */
@DslMarker
annotation class QueryDslMarker
