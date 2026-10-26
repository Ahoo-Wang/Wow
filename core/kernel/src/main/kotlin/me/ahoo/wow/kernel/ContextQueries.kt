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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.read.EventQueries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TrustedQueries

/**
 * The queries a subscription handler reads with (decision D259, [me.ahoo.wow.api.subscribe.SubscriptionContext.queries]),
 * by the tenant of the commit it handles: the node's query API on a node, a test's fake in a domain module's test.
 */
@InternalWowApi
fun interface ContextQueries {
    /** The queries of a handler of a commit of [tenantId]. */
    fun of(tenantId: String): SubscriptionQueries

    @InternalWowApi
    companion object {
        /** No queries: a handler that reads fails, naming what the node lacks. */
        @JvmField
        val NONE: ContextQueries = ContextQueries {
            throw UnsupportedOperationException(
                "No queries are served to the handlers of this node: it has no query plane (wow.query.enabled).",
            )
        }

        /**
         * The queries of [api] by tenant: a tenant's are [QueryApi.of] a caller of that tenant with no owner, space,
         * subject or attribute, the rest [QueryApi.trusted]. For an API that cannot pin a tenant itself, such as a
         * test's fake; the node's pins it as trusted code.
         */
        @JvmStatic
        fun of(api: QueryApi): ContextQueries = ContextQueries { tenantId -> TenantQueries(api, tenantId) }
    }
}

/** [api]'s queries in [tenantId], as a caller of that tenant; see [ContextQueries.of]. */
private class TenantQueries(private val api: QueryApi, override val tenantId: String) : SubscriptionQueries {
    private val queries by lazy { api.of(Caller.of(tenantId = tenantId)) }

    override fun <S : Any> snapshots(stateType: Class<S>): SnapshotQueries<S> = queries.snapshots(stateType)

    override fun snapshots(type: AggregateType): SnapshotQueries<Any> = queries.snapshots(type)

    override fun events(type: AggregateType): EventQueries = queries.events(type)

    @ExperimentalWowApi
    override fun <R : Any> datasets(name: DatasetName, rowType: Class<R>): RowQueries<R> =
        queries.datasets(name, rowType)

    override fun trusted(): TrustedQueries = api.trusted()
}
