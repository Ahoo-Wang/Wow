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
 * Declares a query index led by this state property, so filters and sorts on it stay index scans as the snapshot
 * table grows.
 *
 * The domain only declares the index; the backend builds it. On PostgreSQL it is an expression index whose
 * expressions match, character for character, the casts the SQL compiler emits for the field; on MongoDB it is a
 * compound index. Either way the index always starts with the tenant and the aggregate name, which admission adds to
 * every query, and is partial on not-deleted snapshots. The `wow.query.index.ddl` setting manages it: `auto` creates
 * missing indexes, `validate` only reports them, and `none` leaves the schema alone.
 *
 * ```kotlin
 * data class OrderState(
 *     @field:QueryIndex(with = ["state.createdAt"])
 *     val status: OrderStatus,
 * )
 * ```
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class QueryIndex(
    /** The index name; empty, the default, lets the backend derive it from the aggregate and the indexed paths. */
    val name: String = "",
    /**
     * Further keys of a compound index, in order after the annotated field. Each is a full logical path of the query
     * model, such as `state.createdAt`, as in [QueryAlias].
     */
    val with: Array<String> = [],
)
