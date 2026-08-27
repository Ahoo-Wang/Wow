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

package me.ahoo.wow.serialization.event

import me.ahoo.wow.serialization.JsonRecord
import me.ahoo.wow.serialization.toObject
import tools.jackson.databind.node.ObjectNode

interface StateRecord : JsonRecord {
    fun <S> toState(stateType: Class<S>): S
}

data class StateJsonRecord(override val actual: ObjectNode) : StateRecord {
    override fun <S> toState(stateType: Class<S>): S {
        return actual.toObject(stateType)
    }
}

inline fun <reified S> StateRecord.state(): S {
    return toState(S::class.java)
}
