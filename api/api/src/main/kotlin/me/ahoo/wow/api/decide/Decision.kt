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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FrameworkCodes

/**
 * What a decide function concluded about a command: the events it produces, that it changes nothing, or that it
 * rejects the command. A rejection is a value, not an exception: it is a business outcome the caller is told about,
 * and nothing is written.
 *
 * From Java: a decide function returns `Decision.events(...)`, `Decision.NoOp.INSTANCE` or
 * `Decision.reject(code, message)`, statics of this interface. A Java decide function is plain, not `suspend`: it
 * cannot call [DecideScope.reserve] (a `suspend` function), and a Java saga cannot call
 * [me.ahoo.wow.api.subscribe.SagaContext.call]; the test DSL (`wow-test`) is Kotlin only, so a Java domain's tests
 * are written in Kotlin. A Java facade and a blocking bridge for them are planned for 10.x.
 */
sealed interface Decision {
    /**
     * The command produces [events], appended together as one event stream.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class Events @InternalWowApi constructor(val events: List<Any>) : Decision {
        init {
            require(events.isNotEmpty()) { "A decision to produce events needs at least one event." }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Events && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Events(events=$events)"

        private fun valueFields(): List<Any?> = listOf(events)

        companion object {
            /** An Events of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(events: List<Any>): Events = Events(events)
        }
    }

    /** The command is accepted and changes nothing: nothing is appended. */
    data object NoOp : Decision

    /**
     * The command is refused for [rejection]; nothing is appended.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class Rejected @InternalWowApi constructor(val rejection: Rejection) : Decision {
        override fun equals(other: Any?): Boolean =
            this === other || other is Rejected && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Rejected(rejection=$rejection)"

        private fun valueFields(): List<Any?> = listOf(rejection)

        companion object {
            /** A Rejected of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(rejection: Rejection): Rejected = Rejected(rejection)
        }
    }

    companion object {
        /** Produces [events]. */
        @JvmStatic
        fun events(vararg events: Any): Decision = Events(events.toList())

        /**
         * Rejects the command with [code], a human-readable [message], and the structured [extensions] a client reads
         * instead of parsing the message ([Rejection.extensions]). A [code] without a dot is the local name of a code
         * of the deciding aggregate, which the kernel qualifies as `context.aggregate.code`. The
         * framework's codes (`wow.*`) are not a domain's to use ([Rejection.of]).
         */
        @JvmStatic
        @JvmOverloads
        fun reject(code: String, message: String, extensions: Map<String, Any?> = emptyMap()): Decision =
            Rejected(Rejection.of(code, message, extensions))
    }
}

/**
 * Why a command was refused. [code] is stable and machine-readable (it names an error in the Manifest); [message]
 * is for people. [extensions] are the rejection's structured data, by name, each a JSON value (a
 * string, a number, a boolean, a list, a map, or a data class of them), such as the ids and titles of what blocks the
 * command: the command's result carries them ([me.ahoo.wow.api.result.ErrorInfo.extensions]), and an HTTP answer
 * writes each as an extension member of its problem.
 *
 * Fields may be added: a decide function rejects with [Decision.reject]; construct a rejection itself
 * with [of]. The constructor is internal to Wow.
 */
class Rejection @InternalWowApi constructor(
    val code: String,
    val message: String,
    val extensions: Map<String, Any?> = emptyMap(),
) {
    init {
        require(code.isNotBlank()) { "A rejection needs a code." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is Rejection && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "Rejection(code=$code, message=$message, extensions=$extensions)"

    private fun valueFields(): List<Any?> = listOf(code, message, extensions)

    companion object {

        /**
         * The rejection [code] with [message] and the structured [extensions]. [code] is not one of the framework's
         * (`wow.*`): those carry the framework's meaning, such as an HTTP status and whether a client retries.
         */
        @JvmStatic
        @JvmOverloads
        fun of(code: String, message: String, extensions: Map<String, Any?> = emptyMap()): Rejection {
            require(!code.startsWith(FrameworkCodes.PREFIX)) {
                "The rejection code $code starts with ${FrameworkCodes.PREFIX}, which the framework reserves for its own codes: " +
                    "reject with a code of the aggregate."
            }
            return Rejection(code, message, extensions)
        }
    }
}
