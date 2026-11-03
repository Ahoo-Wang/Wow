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

package me.ahoo.wow.client

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.CommandResult
import tools.jackson.core.JacksonException

/** A command's result as a peer answers it, read by the one reader the client reads every answer with. */
@InternalWowApi
object CommandResults {
    /** The result [body] holds; a [JacksonException] when it holds none. */
    fun read(body: ByteArray): CommandResult = ClientJson.mapper.readValue(body, CommandResult::class.java)

    /** The result [body] holds, or null when it holds none: the body of a query's answer, say. */
    fun readOrNull(body: ByteArray): CommandResult? = try {
        read(body)
    } catch (_: JacksonException) {
        null
    }
}
