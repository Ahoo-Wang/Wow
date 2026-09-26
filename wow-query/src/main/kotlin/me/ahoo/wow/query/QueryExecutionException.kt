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

import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.WowException

/**
 * A query the caller got right that the server failed to run: a mask strategy that failed, a cursor position that
 * does not encode, a stored record or backend row that breaks integrity (an undeclared event `bodyType`, a
 * non-finite number), a storage that timed out or failed on some shards. `InternalServerError` (HTTP 500), never a
 * [me.ahoo.wow.query.schema.QueryViolation]: the caller cannot fix it by changing the request.
 *
 * [message] is returned to clients, so it names what failed without internal detail; [cause] stays server-side.
 */
class QueryExecutionException(
    message: String,
    cause: Throwable? = null,
) : WowException(ErrorCodes.INTERNAL_SERVER_ERROR, message, cause)

/** Throws a [QueryExecutionException] with [message] unless [value] holds. */
inline fun checkExecution(value: Boolean, message: () -> String) {
    if (!value) throw QueryExecutionException(message())
}
