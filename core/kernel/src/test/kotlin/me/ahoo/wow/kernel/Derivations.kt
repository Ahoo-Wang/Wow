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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command

/** The command this execution derives of [request], as a handler that returns it gets it sent. */
fun Derivation.derive(request: CommandRequest<*>): Command<*> = commandsOf(listOf(request)).first()

/** The command a saga's context derives from [request]. */
fun ExecutionSagaContext.derive(request: CommandRequest<*>): Command<*> = commandsOf(listOf(request)).first()
