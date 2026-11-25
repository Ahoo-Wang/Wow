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

import kotlinx.coroutines.reactor.ReactorContext
import me.ahoo.wow.spi.call.DecidingCarrier
import me.ahoo.wow.spi.call.DecidingCommand
import reactor.util.context.Context
import kotlin.coroutines.CoroutineContext

/**
 * Finds the command being decided in the Reactor context a coroutine of `kotlinx-coroutines-reactor` reads
 * ([ReactorContext]): a `mono { }` a decide function returns, or builds and awaits, subscribed by [awaitResult] with
 * the command in its context, so that a Wow client called inside takes its derived request id (#7046).
 */
internal class ReactorDecidingCarrier : DecidingCarrier {
    override fun decidingIn(context: CoroutineContext): DecidingCommand? =
        context[ReactorContext]?.context?.getOrDefault<DecidingCommand>(KEY, null)

    companion object {
        private val KEY = DecidingCommand::class.java

        /** A Reactor context with [deciding] for a carrier to find. */
        fun of(deciding: DecidingCommand): Context = Context.of(KEY, deciding)
    }
}
