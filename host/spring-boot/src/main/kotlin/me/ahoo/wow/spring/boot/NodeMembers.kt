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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.http.internal.ClusterMembers
import me.ahoo.wow.runtime.node.ServiceNode

/**
 * The members of the node's service (review R2.3-1): the addresses the lease groups of its contexts last showed live,
 * as each context's route table holds them. None before the node exists.
 */
internal class NodeMembers(private val node: () -> ServiceNode?) : ClusterMembers {
    override fun contains(address: String): Boolean =
        node()?.contexts?.values?.any { context ->
            context.ownership.routes.members.any { it.address == address }
        } == true
}
