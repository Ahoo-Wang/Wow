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
 * Declares a query index, so filters and sorts on its keys stay index scans as the snapshot table grows.
 *
 * On a state property the index is led by that property, and [on] stays empty. On the state class it declares an index
 * that no state field leads, so [on] must name the leading key. Either way [with] lists the further keys in order.
 * The annotation is repeatable, so one field can lead several compound indexes and one class can declare several.
 *
 * The domain only declares the index; the backend builds it. On PostgreSQL it is an expression index whose
 * expressions match, character for character, the casts the SQL compiler emits for each key; on MongoDB it is a
 * compound index. Every index always starts with the tenant and the aggregate name, which admission adds to every
 * query, and is partial on not-deleted snapshots. `manifest-tools` writes both forms into the Manifest's
 * `x-wow-index` as `{name, keys: [...]}`. The `wow.query.index.ddl` setting manages the indexes: `auto` creates
 * missing ones, `validate` only reports them, and `none` leaves the schema alone.
 *
 * The annotation itself does not check that [on] and [with] name existing paths, or that [on] is set on a class:
 * `manifest-tools` and query-model admission check those.
 *
 * ```kotlin
 * @QueryIndex(on = "ownerId", with = ["lastEventTime"])
 * data class OrderState(
 *     @field:QueryIndex(with = ["state.createdAt"])
 *     @field:QueryIndex(with = ["ownerId"])
 *     val status: OrderStatus,
 * )
 * ```
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
@MustBeDocumented
annotation class QueryIndex(
    /** The index name; empty, the default, lets the backend derive it from the aggregate and the indexed paths. */
    val name: String = "",
    /**
     * The leading key, a full logical path of the query model such as `ownerId`, `lastEventTime` or `state.status`.
     * It is required on a class. On a property it stays empty, because the property itself leads.
     */
    val on: String = "",
    /**
     * Further keys of a compound index, in order after the leading key. Each is a full logical path of the query
     * model, such as `state.createdAt`, as in [QueryAlias].
     */
    val with: Array<String> = [],
)
