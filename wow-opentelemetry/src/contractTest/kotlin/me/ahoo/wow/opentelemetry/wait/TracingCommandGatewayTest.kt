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

package me.ahoo.wow.opentelemetry.wait

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.command.CommandGatewaySpec

class TracingCommandGatewayTest : CommandGatewaySpec() {
    override fun createCommandBus(): CommandBus {
        return InMemoryCommandBus().tracing()
    }

    override fun createMessageBus(): CommandGateway {
        return super.createMessageBus().tracing()
    }
}
