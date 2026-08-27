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

package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.abac.DefaultResourceTagsApplied
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.modeling.command.after.AfterCommandFunction
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class DefaultApplyResourceTagsFunction<C : Any>(
    commandAggregate: CommandAggregate<C, *>,
    afterCommandFunctions: List<AfterCommandFunction<C>>
) : InternalCommandFunction<C>(commandAggregate, afterCommandFunctions) {
    override val supportedType: Class<*> = DefaultApplyResourceTags::class.java

    override fun invokeCommand(exchange: ServerCommandExchange<*>): Mono<*> {
        val applyAbacTags = exchange.message.body as DefaultApplyResourceTags
        return DefaultResourceTagsApplied(applyAbacTags.tags).toMono()
    }
}
