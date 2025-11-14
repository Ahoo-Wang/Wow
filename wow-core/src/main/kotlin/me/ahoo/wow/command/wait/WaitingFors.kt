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

package me.ahoo.wow.command.wait

import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.NamedFunctionInfo
import me.ahoo.wow.infra.ifNotBlank

/**
 * Checks if a function matches the criteria specified by this NamedFunctionInfo.
 * Used to determine if a wait strategy should be notified about a specific function execution.
 * Returns true if all specified criteria (context, processor, name) match or are not specified.
 *
 * @param function The function info to check against this criteria.
 * @return true if the function matches the waiting criteria, false otherwise.
 */
fun NamedFunctionInfo?.isWaitingForFunction(function: FunctionInfo): Boolean {
    if (this == null || isEmpty()) {
        return true
    }
    contextName.ifNotBlank {
        if (!isSameBoundedContext(function)) {
            return false
        }
    }
    processorName.ifNotBlank {
        if (processorName != function.processorName) {
            return false
        }
    }

    name.ifNotBlank {
        return name == function.name
    }
    return true
}
