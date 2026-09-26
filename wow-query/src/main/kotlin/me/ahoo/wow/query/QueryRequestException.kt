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

import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.query.schema.QueryViolation

/**
 * A query request the client got wrong: `IllegalArgument` with the human [errorMsg] and one binding error naming where
 * ([name], a JSON path or `body`) and which rule ([code], stable and machine-readable). [violation] is the catalog
 * entry it renders, when the rule is one ([QueryViolation.Kind.REQUEST]); decoding errors state only a code.
 */
class QueryRequestException(
    override val errorMsg: String,
    val code: String,
    name: String = BODY,
    cause: Throwable? = null,
    val violation: QueryViolation? = null,
) : IllegalArgumentException(errorMsg, cause), ErrorInfo {
    constructor(violation: QueryViolation) : this(
        violation.message,
        violation.code,
        violation.location,
        violation = violation,
    )

    override val errorCode: String
        get() = ErrorCodes.ILLEGAL_ARGUMENT
    override val bindingErrors: List<BindingError> = listOf(BindingError(name, errorMsg, code))

    companion object {
        const val BODY = "body"
    }
}
