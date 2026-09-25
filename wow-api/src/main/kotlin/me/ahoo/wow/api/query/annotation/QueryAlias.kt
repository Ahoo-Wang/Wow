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

package me.ahoo.wow.api.query.annotation

/**
 * Other logical paths a query may use for this field, typically its names before a rename.
 *
 * Each value is a full logical path of the query model, such as `state.oldName`. Filters, sorts, projections and
 * aggregations may use an alias (or a path below it); admission replaces it with the field's canonical path, so
 * results, sort uniqueness, protection and cursors are all decided by the canonical name, and the capability
 * descriptor lists the aliases under the canonical field. Mark a field kept only for old callers with Kotlin's
 * `@Deprecated` instead.
 *
 * ```kotlin
 * data class OrderState(
 *     @field:QueryAlias("state.customer")
 *     val buyer: Buyer,
 * )
 * ```
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class QueryAlias(vararg val value: String)
