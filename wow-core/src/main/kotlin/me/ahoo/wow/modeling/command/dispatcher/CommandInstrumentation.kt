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

package me.ahoo.wow.modeling.command.dispatcher

import me.ahoo.wow.command.ServerCommandExchange
import reactor.core.publisher.Mono

/**
 * Observes the handling of each command at one fixed seam: around the whole command pipeline of
 * [DefaultCommandHandler] (processing, acknowledgement, publication and the `PROCESSED` report). For tracing,
 * metrics and logging, which used to be command filters (V5).
 *
 * An instrumentation must not change the outcome: it subscribes to [around]'s `handling` once and passes its
 * completion or error on. Several instrumentations wrap each other in `@Order` order, the first outermost.
 */
fun interface CommandInstrumentation {
    /**
     * Wraps [handling], the not yet subscribed handling of [exchange].
     *
     * @return a `Mono` that subscribes to [handling] and completes or fails as it does
     */
    fun around(exchange: ServerCommandExchange<*>, handling: Mono<Void>): Mono<Void>
}
