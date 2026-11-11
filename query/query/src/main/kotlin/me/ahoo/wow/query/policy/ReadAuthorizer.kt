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

package me.ahoo.wow.query.policy

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModel

/**
 * Decides whether a caller may read a model at all, before anything of it is read: a query of any model and any
 * operation (a server-sent stream is its query's), a state read and a trace. Which rows the caller sees is the
 * aggregate's access policy's ([me.ahoo.wow.api.access.AccessPolicy]), which fields the masks'; this is the role, the
 * application, the entry: whether the caller may read here at all.
 *
 * An authorizer refuses for who is reading, never for what is asked: a caller without a role or an application, not a
 * query that names a field it may not filter by. A refused caller learns nothing of what it asked for, not even
 * whether an aggregate exists.
 *
 * Every caller is asked, on every entry: the public port, a peer's read on the internal port, and in-process code
 * reading as a caller (`QueryApi.of(caller)`); a [service][Caller.service] is asked as any caller, and the authorizer
 * decides. Trusted in-process code, which reads with no caller, is not asked. A node's authorizers are all asked
 * ([allOf]): [ReadAuthorization.Allowed] means this authorizer has no objection, not that the read is allowed whatever
 * the others say. The host asks every bean of this type, in their order.
 *
 * A refusal is answered by its [code][ReadAuthorization.Denied.code], `403 wow.auth.forbidden` unless it says
 * otherwise: over HTTP as that problem, in process as the problem thrown.
 */
fun interface ReadAuthorizer {
    suspend fun authorize(caller: Caller, read: ReadAction): ReadAuthorization

    companion object {
        /** Allows every read. */
        @JvmField
        val ALLOW_ALL: ReadAuthorizer = ReadAuthorizer { _, _ -> ReadAuthorization.Allowed }

        /**
         * [authorizers] as one: asked in their order, the first [ReadAuthorization.Denied] is the answer and the rest
         * are not asked; a read every one allows is allowed. [ALLOW_ALL] for none, the authorizer itself for one.
         */
        @JvmStatic
        fun allOf(authorizers: List<ReadAuthorizer>): ReadAuthorizer = when (authorizers.size) {
            0 -> ALLOW_ALL
            1 -> authorizers.single()
            else -> AllOf(authorizers)
        }
    }

    private class AllOf(private val authorizers: List<ReadAuthorizer>) : ReadAuthorizer {
        override suspend fun authorize(caller: Caller, read: ReadAction): ReadAuthorization {
            authorizers.forEach { authorizer ->
                val decision = authorizer.authorize(caller, read)
                if (decision is ReadAuthorization.Denied) return decision
            }
            return ReadAuthorization.Allowed
        }

        override fun toString(): String = "ReadAuthorizer.allOf($authorizers)"
    }
}

/**
 * What a caller reads: the [model] of [type] (an aggregate type, or a dataset's context and name), by [operation],
 * from [entry]. A state read and a trace read the [snapshot][QueryModel.SNAPSHOT] model, by [QueryType.STATE] and
 * [QueryType.TRACE]: the names `wow.callers.<service>.reads` gives them are `<type>.state`.
 */
class ReadAction @InternalWowApi constructor(
    val type: AggregateType,
    val model: QueryModel,
    val operation: QueryType,
    val entry: QueryEntry,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ReadAction && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ReadAction(type=$type, model=$model, operation=$operation, entry=$entry)"

    private fun valueFields(): List<Any?> = listOf(type, model, operation, entry)

    companion object {
        /** A ReadAction of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(type: AggregateType, model: QueryModel, operation: QueryType, entry: QueryEntry): ReadAction =
            ReadAction(type, model, operation, entry)
    }
}

/** A read authorizer's answer: [Allowed], or [Denied] with its [Denied.message] and [Denied.code]. */
sealed interface ReadAuthorization {
    data object Allowed : ReadAuthorization

    /**
     * A refusal of the caller, answered with [code]: `wow.auth.forbidden` (403) unless the caller is refused for
     * something the error code table names otherwise, such as a request without an application
     * (`wow.query.malformed`, 400). The code is one of that table's ([FrameworkErrors]), each with its status.
     */
    class Denied @InternalWowApi constructor(
        val message: String,
        val code: String = FrameworkCodes.AUTH_FORBIDDEN,
    ) : ReadAuthorization {
        init {
            require(FrameworkErrors.ALL.any { it.code == code }) {
                "A read is refused with one of the framework's error codes, not $code."
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Denied && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Denied(message=$message, code=$code)"

        private fun valueFields(): List<Any?> = listOf(message, code)

        companion object {
            /** A Denied of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            @JvmOverloads
            fun of(message: String, code: String = FrameworkCodes.AUTH_FORBIDDEN): Denied = Denied(message, code)
        }
    }
}

/** The binding error code of a read a [ReadAuthorizer] refused. */
internal const val READ_DENIED: String = "READ_DENIED"

/**
 * Asks this authorizer whether [caller] may make [read], and throws its refusal as the problem of its code
 * ([QueryRequestException]); trusted in-process code, which reads with no caller, is not asked.
 */
internal suspend fun ReadAuthorizer.authorizeOrThrow(caller: QueryCaller, read: () -> ReadAction) {
    if (this === ReadAuthorizer.ALLOW_ALL) return
    val who = caller.caller ?: return
    val decision = authorize(who, read())
    if (decision is ReadAuthorization.Denied) {
        throw QueryRequestException(decision.message, READ_DENIED, problem = decision.code)
    }
}

/** This entry as a read names it: an entry nobody stated is in process. */
internal fun QueryEntry.admitted(): QueryEntry = if (this == QueryEntry.UNSPECIFIED) QueryEntry.IN_PROCESS else this
