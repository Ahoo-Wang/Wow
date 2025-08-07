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

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.naming.Materialized
import me.ahoo.wow.api.naming.Named

interface NamedFunctionInfo : ProcessorInfo, Named {
    /**
     * The name of the function.
     *
     * Under the same processor, the name is unique.
     */
    override val name: String

    @JsonIgnore
    fun isEmpty(): Boolean {
        return contextName.isEmpty() && processorName.isEmpty() && name.isEmpty()
    }
}

data class NamedFunctionInfoData(
    override val contextName: String,
    override val processorName: String,
    override val name: String
) : NamedFunctionInfo, Materialized {
    companion object {
        val EMPTY = NamedFunctionInfoData("", "", "")
    }
}

fun NamedFunctionInfo.materialize(): NamedFunctionInfoData {
    if (this is NamedFunctionInfoData) {
        return this
    }
    return NamedFunctionInfoData(
        contextName = contextName,
        processorName = processorName,
        name = name
    )
}
