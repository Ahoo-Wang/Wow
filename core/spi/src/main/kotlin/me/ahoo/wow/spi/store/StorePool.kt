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

package me.ahoo.wow.spi.store

import me.ahoo.wow.api.annotation.WowSpi

/**
 * The connections a store draws from, as its pool says them: a facet of a [Store] ([Store.facet]) whose pool can say
 * them (HikariCP's, the MongoDB driver's, the Elasticsearch client's), so that the node bounds the store's queries by
 * its pool and reads its write pressure from it. A store whose pool says nothing has none: its queries take the
 * default bound and its write pressure is its append latency alone.
 *
 * Every member reads what the pool already counts: it does no I/O, and the node calls [waitingForConnection] about
 * once a second.
 */
@WowSpi
interface StorePool {
    /** The most connections the pool the store's queries draw from opens; null when that pool does not say. */
    val queryConnections: Int?

    /**
     * Whether the queries draw from the pool the appends draw from, so that a query takes a connection a command may
     * wait for.
     */
    val sharedWithAppends: Boolean

    /**
     * How many callers wait now for a connection of the pool the appends draw from (the fast half of the store's
     * write pressure); null when the pool does not say.
     */
    fun waitingForConnection(): Int?
}
