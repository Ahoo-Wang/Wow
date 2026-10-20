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

package me.ahoo.wow.query.state

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.QueryBindingError
import me.ahoo.wow.query.QueryError
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.filter.QueryType

/**
 * What a state point read asks for, as its observers and its audit see it (review R4-Q, Q-7): the shape of the read,
 * never the id, the version or the time it names. [queryType] is what its observers are told.
 */
@InternalWowApi
enum class StateRead(val queryType: QueryType) {
    /** The latest state. */
    LATEST(QueryType.STATE),

    /** The state at a version. */
    VERSION(QueryType.STATE),

    /** The state as of a time. */
    AT(QueryType.STATE),

    /** The commits from a version. */
    TRACE(QueryType.TRACE),
}

/**
 * A state read that finds nothing the caller may see (decision D254 ④, review R4-Q Q-5): no such state, or one the
 * caller may not see. Every case is answered alike, as `wow.aggregate.not-found`, so a caller cannot tell a state it
 * may not see from one that does not exist; only the audit of the read learns the [reason] (its error code reads
 * `wow.aggregate.not-found:<reason>`).
 */
@InternalWowApi
class StateNotFoundException(val ref: AggregateRef, val reason: Reason, detail: String = "No state of $ref.") :
    RuntimeException(detail) {
    /** Why the caller is answered that there is no state. */
    @InternalWowApi
    enum class Reason {
        /** There is none: no commit, or none at the version or time asked. */
        MISSING,

        /** The state is outside the caller's scope: an owner or a space the caller names and the state does not have. */
        OUT_OF_SCOPE,

        /** The query policies of its type (ABAC, the application's) hide it from the caller. */
        HIDDEN,
    }

    /** The code the caller is answered with. */
    val errorCode: String
        get() = FrameworkErrors.AGGREGATE_NOT_FOUND
}

/**
 * A state read the client got wrong (review R4-Q, Q-4): a parameter it cannot read or that is out of range, or a
 * state too far from the snapshot to replay. It is a query's [invalid][QueryErrors.INVALID] request with one
 * violation naming the parameter ([name]) and the rule ([code], one of `me.ahoo.wow.api.query.QueryErrorCodes`).
 */
@InternalWowApi
class InvalidStateReadException(override val errorMsg: String, val code: String, val name: String) :
    IllegalArgumentException(errorMsg), QueryError {
    override val errorCode: String
        get() = QueryErrors.INVALID

    override val bindingErrors: List<QueryBindingError>
        get() = listOf(QueryBindingError(name, errorMsg, code))
}
