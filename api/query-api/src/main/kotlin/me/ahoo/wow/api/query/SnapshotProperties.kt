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

package me.ahoo.wow.api.query

/**
 * The fields of a snapshot record a query names, as [QueryField]s: the system fields and
 * [STATE], under which the aggregate's state lies. Each name is its path in upper snake case, a nested segment
 * after `__`: [OPERATOR__ID] is `operator.id`.
 *
 * The compiler generates a `<State>Properties` object for the state of each aggregate whose module has
 * `wow-query-api` on its class path. It extends this class, so it carries these fields beside the state's own, which
 * it names after the Kotlin properties of their full path: `OrderStateProperties.STATE__STATUS` is `state.status`,
 * and `STATE__ITEMS__PRODUCT_ID` is `state.items.productId`, named inside `STATE__ITEMS.elementMatch { }`. Without a
 * state at hand, the system fields are on the companion: `SnapshotProperties.LAST_EVENT_TIME`.
 *
 * ```kotlin
 * val query = listQuery {
 *     filter {
 *         OrderStateProperties.STATE__STATUS eq OrderStatus.PAID
 *         OrderStateProperties.OPERATOR__ID eq "user-1"
 *     }
 *     sort { OrderStateProperties.LAST_EVENT_TIME.desc() }
 * }
 * ```
 */
@Suppress("PropertyName", "VariableNaming")
open class SnapshotProperties protected constructor() {
    /** The aggregate's tenant. */
    @JvmField
    val TENANT_ID: QueryField<String> = QueryField.of("tenantId")

    /** The aggregate's id, unique within its tenant. */
    @JvmField
    val AGGREGATE_ID: QueryField<String> = QueryField.of("aggregateId")

    /** The aggregate's version: the number of its events. */
    @JvmField
    val VERSION: QueryField<Int> = QueryField.of("version")

    /** The aggregate's owner, `null` when it has none. */
    @JvmField
    val OWNER_ID: QueryField<String?> = QueryField.of("ownerId")

    /** The aggregate's space, `null` when it has none. */
    @JvmField
    val SPACE_ID: QueryField<String?> = QueryField.of("spaceId")

    /** Whether the aggregate is deleted; a query sees only the others unless it filters on the deletion state. */
    @JvmField
    val DELETED: QueryField<Boolean> = QueryField.of("deleted")

    /** The aggregate's tags, a map of string lists; [tag] names one of them. */
    @JvmField
    val TAGS: QueryField<Any?> = QueryField.of("tags")

    /** When the aggregate got its first event, in epoch milliseconds. */
    @JvmField
    val FIRST_EVENT_TIME: QueryField<Long> = QueryField.of("firstEventTime")

    /** When the aggregate got its last event, in epoch milliseconds. */
    @JvmField
    val LAST_EVENT_TIME: QueryField<Long> = QueryField.of("lastEventTime")

    /** Who sent the last command: `{id, tenantId, crossScope}`, or `null` for an anonymous caller. */
    @JvmField
    val OPERATOR: QueryField<Any?> = QueryField.of("operator")

    @JvmField
    val OPERATOR__ID: QueryField<String?> = QueryField.of("operator.id")

    @JvmField
    val OPERATOR__TENANT_ID: QueryField<String?> = QueryField.of("operator.tenantId")

    @JvmField
    val OPERATOR__CROSS_SCOPE: QueryField<Boolean?> = QueryField.of("operator.crossScope")

    /** Who sent the command that created the aggregate, as [OPERATOR]. */
    @JvmField
    val FIRST_OPERATOR: QueryField<Any?> = QueryField.of("firstOperator")

    @JvmField
    val FIRST_OPERATOR__ID: QueryField<String?> = QueryField.of("firstOperator.id")

    @JvmField
    val FIRST_OPERATOR__TENANT_ID: QueryField<String?> = QueryField.of("firstOperator.tenantId")

    @JvmField
    val FIRST_OPERATOR__CROSS_SCOPE: QueryField<Boolean?> = QueryField.of("firstOperator.crossScope")

    /**
     * The schedules the aggregate keeps: each rule by its key, `{type, ...}` as Wow's JSON writes it
     * (`{type: "cron", expression, zone, misfire}`); [schedule] names one.
     */
    @JvmField
    val SCHEDULES: QueryField<Any?> = QueryField.of("schedules")

    /** The aggregate's state. */
    @JvmField
    val STATE: QueryField<Any?> = QueryField.of("state")

    /** The tag [name] of [TAGS]: `tags.<name>`. */
    fun tag(name: String): QueryField<*> = TAGS.append(QueryField(name))

    /** The schedule [key] of [SCHEDULES]: `schedules.<key>`, its rule. */
    fun schedule(key: String): QueryField<*> = SCHEDULES.append(QueryField(key))

    /** The system fields alone. */
    companion object : SnapshotProperties()
}
