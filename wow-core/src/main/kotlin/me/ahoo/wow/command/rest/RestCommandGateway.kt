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

package me.ahoo.wow.command.rest

import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import reactor.core.publisher.Mono

interface RestCommandGateway {

    fun send(commandRequest: CommandRequest): Mono<CommandResult>

    data class CommandRequest(
        val body: Any,
        val waitStrategy: WaitStrategy = WaitStrategy(),
        val tenantId: String? = null,
        val aggregateId: String? = null,
        val aggregateVersion: Int? = null,
        val requestId: String? = null,
        val context: String? = null,
        val aggregate: String? = null,
        val serviceUri: String? = null,
        val commandType: String? = null
    )

    data class WaitStrategy(
        val waitStage: CommandStage = CommandStage.PROCESSED,
        val waitContext: String? = null,
        val waitProcessor: String? = null,
        val waitTimeout: Long? = null
    )
}
