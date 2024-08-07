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

package me.ahoo.wow.api.messaging.function

import me.ahoo.wow.api.naming.Materialized

data class FunctionInfoData(
    override val functionKind: FunctionKind,
    override val contextName: String,
    override val processorName: String,
    override val name: String
) : FunctionInfo, Materialized {
    companion object {
        private const val UNKNOWN = "Unknown"
        fun unknown(
            functionKind: FunctionKind,
            contextName: String,
            processorName: String = UNKNOWN,
            functionName: String = UNKNOWN
        ): FunctionInfoData {
            return FunctionInfoData(functionKind, contextName, processorName, functionName)
        }
    }
}

fun FunctionInfo.materialize(): FunctionInfoData {
    if (this is Materialized) {
        return this as FunctionInfoData
    }
    return FunctionInfoData(
        contextName = contextName,
        processorName = processorName,
        name = name,
        functionKind = functionKind,
    )
}
