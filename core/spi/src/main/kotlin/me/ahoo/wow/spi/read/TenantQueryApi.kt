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

package me.ahoo.wow.spi.read

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.SubscriptionQueries

/**
 * A [NodeQueryApi] that builds the reads of a subscription handler itself ([tenant]), which the node asks of every
 * [QueryApi] it hands its handlers: a provider's side of the read API, not an entry point of an application, which
 * reads as a caller ([QueryApi.of]) or as trusted code ([NodeQueryApi.trusted]).
 *
 * The node's own API implements it, pinning the tenant of the commit as trusted code, and so does a test's
 * [QueryApi] that must read as the node does (`:test-query`'s memory queries). Any other [QueryApi], a test's fake
 * say, serves a handler as a caller of the commit's tenant with no owner, space or subject, the rest
 * [NodeQueryApi.trusted].
 */
@WowSpi
interface TenantQueryApi : NodeQueryApi {
    /**
     * The queries of a subscription handler of a commit of [tenantId], which its context hands it: they read in
     * [tenantId] alone, and answer [SubscriptionQueries.tenantId] with it.
     */
    fun tenant(tenantId: String): SubscriptionQueries
}
