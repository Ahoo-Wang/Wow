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

package me.ahoo.wow.reactor

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import me.ahoo.wow.spi.call.DecidingCommand
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Awaits what a Reactor-style handler returned, for the kernel (users may keep writing Reactor; only
 * this adapter knows it). A [Mono] yields its value or nothing; any other [Publisher] (a `Flux`) yields everything
 * it emits, as a list of events. The code `wow-compiler` generates passes the result on to `decisionOf` or
 * `eventsOf`.
 *
 * Awaited while deciding, the publisher is subscribed with the command being decided in its Reactor context
 * ([ReactorDecidingCarrier]): a decide function calls a suspending Wow client inside `mono { }`, whose coroutine reads
 * that context, so a call that stands takes its derived request id there too (#7046).
 */
suspend fun awaitResult(result: Publisher<*>): Any? {
    val deciding = currentCoroutineContext()[DecidingCommand]
    return when (result) {
        is Mono<*> -> (if (deciding == null) result else result.contextWrite(ReactorDecidingCarrier.of(deciding)))
            .awaitSingleOrNull()

        else -> (if (deciding == null) result else Flux.from(result).contextWrite(ReactorDecidingCarrier.of(deciding)))
            .asFlow().toList()
    }
}
