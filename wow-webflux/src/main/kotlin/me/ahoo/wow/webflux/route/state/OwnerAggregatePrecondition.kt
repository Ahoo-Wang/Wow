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

package me.ahoo.wow.webflux.route.state

import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.modeling.command.IllegalAccessOwnerAggregateException
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.webflux.route.identity.RequestIdentity
import me.ahoo.wow.webflux.route.identity.identity
import org.springframework.web.reactive.function.server.ServerRequest

/** Checks that an owned aggregate's owner is the owner the request states, read only for an owned aggregate. */
internal class OwnerAggregatePrecondition(
    private val owner: OwnerPolicy,
    private val requestOwnerId: () -> String?,
) {
    constructor(identity: RequestIdentity, owner: OwnerPolicy) : this(owner, { identity.readOwnerId() })

    /** The request's owner as its route states it. */
    constructor(request: ServerRequest, owner: OwnerPolicy) : this(owner, {
        request.identity(owner).readOwnerId()
    })

    fun <S : Any> check(stateAggregate: StateAggregate<S>) {
        if (!owner.owned) {
            return
        }
        val ownerId = requireNotNull(requestOwnerId())
        if (stateAggregate.ownerId != ownerId) {
            throw IllegalAccessOwnerAggregateException(stateAggregate.aggregateId)
        }
    }
}
