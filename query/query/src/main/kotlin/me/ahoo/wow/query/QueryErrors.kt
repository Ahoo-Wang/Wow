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

package me.ahoo.wow.query

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.FrameworkErrors

/**
 * The framework error codes of the query side (decisions D130, D150), as the edge answers them:
 *
 * - [MALFORMED] (400): the request body cannot be read as a query;
 * - [INVALID] (400): the query breaks a rule; each violation carries its stable code, such as `UNKNOWN_FIELD`
 *   (`me.ahoo.wow.api.query.QueryErrorCodes`);
 * - [FAILED] (500): a query the caller got right that the server failed to run;
 * - [TIMEOUT] (504, no `Retry-After`, decision D254 ③): the query ran out of its execution time; running it again would run as long;
 * - [STORAGE_UNAVAILABLE] (503): the storage could not be reached or did not answer in time; the same query may pass
 *   later.
 * - [MIN_VERSION_NOT_REACHED] (409, with `Retry-After`): a state read asked for a version the aggregate has not reached
 *   within its wait ([me.ahoo.wow.api.read.MinVersionNotReachedException]);
 * - [BUSY] (429, with `Retry-After`): the query's store already runs as many queries as its bulkhead allows
 *   ([QueryBulkhead], review R3.4-2); the query was not run, and the same query may pass later.
 * - [DATASET_UNAVAILABLE] (409): the dataset is served but cannot be queried now, such as one whose definition needs
 *   revision (decision D259 7.2b-2); the problem names the reason, in fixed words.
 *
 * They are the codes of [FrameworkErrors], whose table gives each its HTTP status; a caller the query scope does not
 * admit is answered with [FORBIDDEN].
 */
@InternalWowApi
object QueryErrors {
    const val MALFORMED: String = FrameworkErrors.QUERY_MALFORMED
    const val INVALID: String = FrameworkErrors.QUERY_INVALID
    const val FAILED: String = FrameworkErrors.QUERY_FAILED
    const val TIMEOUT: String = FrameworkErrors.QUERY_TIMEOUT
    const val STORAGE_UNAVAILABLE: String = FrameworkErrors.STORAGE_UNAVAILABLE
    const val BUSY: String = FrameworkErrors.QUERY_BUSY

    /** A `single` query that matched no record, whatever its model (review R4-Q, Q-4). */
    const val NOT_FOUND: String = FrameworkErrors.QUERY_NOT_FOUND

    /** A dataset that is not served, or not to the caller (review R4-Q, Q-4). */
    const val DATASET_NOT_FOUND: String = FrameworkErrors.QUERY_DATASET_NOT_FOUND

    /** A state read of a minimum version the aggregate has not reached (review R4-Q, Q-13). */
    const val MIN_VERSION_NOT_REACHED: String = FrameworkErrors.QUERY_MIN_VERSION_NOT_REACHED
    const val DATASET_UNAVAILABLE: String = FrameworkErrors.QUERY_DATASET_UNAVAILABLE

    /** The caller may not run this query. */
    const val FORBIDDEN: String = FrameworkErrors.AUTH_FORBIDDEN
}

/** A query error as the edge answers it: its framework [errorCode], its text and the violations of the request. */
interface QueryError {
    /** One of [QueryErrors]. */
    val errorCode: String

    /** The text the caller is answered with: it names what failed without internal detail. */
    val errorMsg: String

    /** Where and which rule a rejected request broke; empty for a server fault. */
    val bindingErrors: List<QueryBindingError>
        get() = emptyList()
}

/**
 * One rule a request broke: [name] is where (a JSON path, the absolute logical field, `""` for the model or `body`),
 * [code] which rule, stable and machine-readable (`me.ahoo.wow.api.query.QueryErrorCodes`).
 */
class QueryBindingError @InternalWowApi constructor(val name: String, val msg: String, val code: String) {
    override fun equals(other: Any?): Boolean =
        this === other || other is QueryBindingError && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "QueryBindingError(name=$name, msg=$msg, code=$code)"

    private fun valueFields(): List<Any?> = listOf(name, msg, code)

    companion object {
        /** A [QueryBindingError] of the given values. */
        @JvmStatic
        fun of(name: String, msg: String, code: String): QueryBindingError = QueryBindingError(name, msg, code)
    }
}
