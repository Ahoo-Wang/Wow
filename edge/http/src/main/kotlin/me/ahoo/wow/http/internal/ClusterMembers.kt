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

package me.ahoo.wow.http.internal

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The internal addresses of the nodes of this service's cluster (review R2.3-1): those [HttpSignalSender] sends the
 * cluster token to, as the lease store's members show them.
 */
@InternalWowApi
fun interface ClusterMembers {
    /** Whether [address] is the internal address of a live node of this service. */
    fun contains(address: String): Boolean

    @InternalWowApi
    companion object {
        /** No member known: the cluster token goes nowhere. */
        @JvmField
        val NONE: ClusterMembers = ClusterMembers { false }
    }
}
