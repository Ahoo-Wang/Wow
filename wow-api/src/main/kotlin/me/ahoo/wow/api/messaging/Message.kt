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

package me.ahoo.wow.api.messaging

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.naming.NamedBoundedContext

/**
 * Messages are the nexus of collaboration.
 *
 * @author ahoo wang
 */
interface Message<out T> : Identifier {
    val header: Header
    val body: T
    val createTime: Long

    /**
     *
     * 合并 header 并返回一个新的 Message.
     *
     * @param additionalSource additional Source
     * @return new message instance
     */
    fun mergeHeader(additionalSource: Map<String, String>): Message<T>
}

interface NamedBoundedContextMessage<out T> : Message<T>, NamedBoundedContext

interface NamedMessage<out T> : NamedBoundedContextMessage<T>, Named
