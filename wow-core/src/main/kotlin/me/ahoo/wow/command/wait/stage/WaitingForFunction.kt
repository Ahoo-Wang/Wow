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

package me.ahoo.wow.command.wait.stage

import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.messaging.function.NullableFunctionInfoCapable
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.isWaitingForFunction

abstract class WaitingForFunction : WaitingForAfterProcessed(), NullableFunctionInfoCapable<NamedFunctionInfoData> {
    override val materialized: WaitStrategy.Materialized by lazy {
        Materialized(
            waitCommandId = waitCommandId,
            stage = stage,
            function = function
        )
    }

    override fun isWaitingFor(signal: WaitSignal): Boolean {
        if (!super.isWaitingFor(signal)) {
            return false
        }
        return this.function.isWaitingForFunction(signal.function)
    }
}
