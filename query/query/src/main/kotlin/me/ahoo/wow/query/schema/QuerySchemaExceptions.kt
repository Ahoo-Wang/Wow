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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.QueryBindingError
import me.ahoo.wow.query.QueryError
import me.ahoo.wow.query.QueryErrors

/** A failure of a query schema: of a query against it, or of the schema itself. */
@InternalWowApi
sealed class QuerySchemaException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause), QueryError {
    override val errorMsg: String
        get() = checkNotNull(message)
}

/**
 * A query that does not fit its model or the model's storage: [QueryErrors.INVALID] with the [violation] it breaks.
 * Without a violation it is a schema that does not validate, a server fault ([QueryErrors.FAILED]).
 */
@InternalWowApi
class QuerySchemaValidationException(
    message: String,
    cause: Throwable? = null,
    /** The structured violation, when the check that failed states one. */
    val violation: QueryViolation? = null,
) : QuerySchemaException(message, cause) {
    constructor(violation: QueryViolation) : this(violation.message, violation = violation)

    override val errorCode: String
        get() = if (violation == null) QueryErrors.FAILED else QueryErrors.INVALID

    /** One binding error per violation: its field (or `""` for the model), its text and its stable code. */
    override val bindingErrors: List<QueryBindingError> =
        if (violation == null) emptyList() else listOf(QueryBindingError(violation.location, message, violation.code))
}

/** Declarations or storage facts that contradict each other: a server fault ([QueryErrors.FAILED]). */
internal class QuerySchemaConflictException(
    message: String,
    cause: Throwable? = null,
) : QuerySchemaException(message, cause) {
    override val errorCode: String
        get() = QueryErrors.FAILED
}

/** A model whose schema cannot be loaded, such as one without a query backend: [QueryErrors.FAILED]. */
@InternalWowApi
class QuerySchemaUnavailableException(
    message: String,
    cause: Throwable? = null,
) : QuerySchemaException(message, cause) {
    override val errorCode: String
        get() = QueryErrors.FAILED
}
