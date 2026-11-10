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
package me.ahoo.wow.runtime.admission

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/** How admission reads a request's body as its command type, step 4 of admission ([CommandGateway]), with [mapper]. */
internal class CommandBodies(private val mapper: JsonMapper) {
    /**
     * Why [body] cannot be taken, or null when it can: it writes `\u0000` in a string or a key ([AdmissionLimits]); a
     * command object ([CommandBody.Value]) is checked as the JSON it is written as, which it must be.
     */
    fun bodyProblem(body: CommandBody): String? {
        val nul = when (body) {
            is CommandBody.Bytes -> AdmissionLimits.writesNul(body.bytes)
            is CommandBody.Tree -> AdmissionLimits.holdsNul(body.node)
            is CommandBody.Value -> try {
                AdmissionLimits.holdsNul(mapper.valueToTree(body.command))
            } catch (failure: JacksonException) {
                return "The command ${body.command.javaClass.name} is not written as JSON: ${failure.originalMessage}"
            }
        }
        return if (nul) "The body writes \\u0000 in a string, which no store keeps." else null
    }

    /**
     * [body] read as [type], or null when it does not decode to one; a command object ([CommandBody.Value]) is taken as
     * it is when it is one.
     */
    fun decode(body: CommandBody, type: Class<*>): Decoded? = try {
        when (body) {
            is CommandBody.Bytes -> mapper.readValue(body.bytes, type)
            is CommandBody.Tree -> mapper.treeToValue(body.node, type)
            is CommandBody.Value -> body.command.takeIf { type.isInstance(it) }
        }?.let(::Decoded)
    } catch (_: JacksonException) {
        null
    }
}

internal class Decoded(val value: Any)
