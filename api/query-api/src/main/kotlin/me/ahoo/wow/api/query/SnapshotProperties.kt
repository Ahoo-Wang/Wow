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
 * The fields of a snapshot record a query names (decisions D128, D163), as [QueryField]s: the system fields and
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
 *         OrderStateProperties.STATE__STATUS eq "PAID"
 *         OrderStateProperties.OPERATOR__ID eq "user-1"
 *     }
 *     sort { OrderStateProperties.LAST_EVENT_TIME.desc() }
 * }
 * ```
 */
@Suppress("PropertyName", "VariableNaming")
open class SnapshotProperties protected constructor() {
    /** The aggregate's tenant. */
    val TENANT_ID: QueryField = QueryField("tenantId")

    /** The aggregate's id, unique within its tenant. */
    val AGGREGATE_ID: QueryField = QueryField("aggregateId")

    /** The aggregate's version: the number of its events. */
    val VERSION: QueryField = QueryField("version")

    /** The aggregate's owner, `null` when it has none. */
    val OWNER_ID: QueryField = QueryField("ownerId")

    /** The aggregate's space, `null` when it has none. */
    val SPACE_ID: QueryField = QueryField("spaceId")

    /** Whether the aggregate is deleted; a query sees only the others unless it filters on the deletion state. */
    val DELETED: QueryField = QueryField("deleted")

    /** The aggregate's tags, a map of string lists; [tag] names one of them. */
    val TAGS: QueryField = QueryField("tags")

    /** When the aggregate got its first event, in epoch milliseconds. */
    val FIRST_EVENT_TIME: QueryField = QueryField("firstEventTime")

    /** When the aggregate got its last event, in epoch milliseconds. */
    val LAST_EVENT_TIME: QueryField = QueryField("lastEventTime")

    /** Who sent the last command: `{id, tenantId, crossScope}`, or `null` for an anonymous caller. */
    val OPERATOR: QueryField = QueryField("operator")
    val OPERATOR__ID: QueryField = QueryField("operator.id")
    val OPERATOR__TENANT_ID: QueryField = QueryField("operator.tenantId")
    val OPERATOR__CROSS_SCOPE: QueryField = QueryField("operator.crossScope")

    /** Who sent the command that created the aggregate, as [OPERATOR]. */
    val FIRST_OPERATOR: QueryField = QueryField("firstOperator")
    val FIRST_OPERATOR__ID: QueryField = QueryField("firstOperator.id")
    val FIRST_OPERATOR__TENANT_ID: QueryField = QueryField("firstOperator.tenantId")
    val FIRST_OPERATOR__CROSS_SCOPE: QueryField = QueryField("firstOperator.crossScope")

    /** The aggregate's state. */
    val STATE: QueryField = QueryField("state")

    /** The tag [name] of [TAGS]: `tags.<name>`. */
    fun tag(name: String): QueryField = TAGS.append(QueryField(name))

    /** The system fields alone. */
    companion object : SnapshotProperties()
}
