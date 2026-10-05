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

package me.ahoo.wow.messaging

import reactor.core.publisher.Mono

/**
 * The outcome of handing a message to the local bus ([LocalMessageBus.handOff]).
 *
 * - [accepted]: the message entered the local sink of every routed receiver. Nobody waits for the receivers' demand.
 * - [admission]: whether every routed receiver then admitted it for processing (`true`), or the delivery was
 *   rejected before that, for example because a receiver closed (`false`). It completes when the receivers decide,
 *   so a sender must not wait for it on its own send path.
 */
class LocalHandoff private constructor(
    val accepted: Boolean,
    val admission: Mono<Boolean>,
) {
    companion object {
        /** Not handed off: no routable receiver, a full or closed sink. */
        @JvmField
        val REFUSED: LocalHandoff = LocalHandoff(false, Mono.just(false))

        /** Handed off; [admission] tells whether it was admitted. */
        fun accepted(admission: Mono<Boolean>): LocalHandoff = LocalHandoff(true, admission)
    }

    override fun toString(): String = if (accepted) "LocalHandoff(accepted)" else "LocalHandoff(refused)"
}
