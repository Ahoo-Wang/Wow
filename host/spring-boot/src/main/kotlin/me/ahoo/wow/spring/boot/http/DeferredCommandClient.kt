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

package me.ahoo.wow.spring.boot.http

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult

/**
 * The service's [CommandClient], built on its first use from [create]. The client sends through the node's gateway,
 * which the node assembles with the application's transformers, validators, authorizers and subscriptions: one of them
 * may take the client, and is then created before the node is. Building the client only when it first sends, by which
 * time the node exists, keeps that from being a dependency cycle.
 */
internal class DeferredCommandClient(create: () -> CommandClient) : CommandClient {
    private val delegate by lazy(create)

    override fun of(caller: Caller): CommandClient = DeferredCommandClient { delegate.of(caller) }

    override suspend fun send(request: CommandRequest<*>): CommandResult = delegate.send(request)

    override suspend fun wait(request: CommandRequest<*>): CommandResult = delegate.wait(request)

    override fun stream(request: CommandRequest<*>): Flow<CommandResult> = delegate.stream(request)
}
