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

package me.ahoo.wow.api.decide

/**
 * What a decide function concluded about a command: the events it produces, that it changes nothing, or that it
 * rejects the command. A rejection is a value, not an exception: it is a business outcome the caller is told about,
 * and nothing is written.
 */
sealed interface Decision {
    /** The command produces [events], appended together as one event stream. */
    data class Events(val events: List<Any>) : Decision {
        init {
            require(events.isNotEmpty()) { "A decision to produce events needs at least one event." }
        }
    }

    /** The command is accepted and changes nothing: nothing is appended. */
    data object NoOp : Decision

    /** The command is refused for [rejection]; nothing is appended. */
    data class Rejected(val rejection: Rejection) : Decision

    companion object {
        /** Produces [events]. */
        fun events(vararg events: Any): Decision = Events(events.toList())

        /**
         * Rejects the command with [code], a human-readable [message], and the structured [extensions] a client reads
         * instead of parsing the message ([Rejection.extensions]).
         */
        fun reject(code: String, message: String, extensions: Map<String, Any?> = emptyMap()): Decision =
            Rejected(Rejection(code, message, extensions))
    }
}

/**
 * Why a command was refused. [code] is stable and machine-readable (it names an error in the Manifest); [message]
 * is for people. [extensions] (decision D211) are the rejection's structured data, by name, each a JSON value (a
 * string, a number, a boolean, a list, a map, or a data class of them), such as the ids and titles of what blocks the
 * command: the command's result carries them ([me.ahoo.wow.api.result.ErrorInfo.extensions]), and an HTTP answer
 * writes each as an extension member of its problem.
 */
data class Rejection(val code: String, val message: String, val extensions: Map<String, Any?> = emptyMap()) {
    init {
        require(code.isNotBlank()) { "A rejection needs a code." }
    }
}
