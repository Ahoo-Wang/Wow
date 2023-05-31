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

package me.ahoo.wow.webflux.exception

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.ErrorCodes
import org.springframework.http.HttpStatus

object ErrorHttpStatusMapping {
    private val registrar = mutableMapOf<String, HttpStatus>()

    init {
        register(ErrorCodes.SUCCEEDED, HttpStatus.OK)
        register(ErrorCodes.NOT_FOUND, HttpStatus.NOT_FOUND)
        register(ErrorCodes.BAD_REQUEST, HttpStatus.BAD_REQUEST)
        register(ErrorCodes.ILLEGAL_ARGUMENT, HttpStatus.BAD_REQUEST)
        register(ErrorCodes.ILLEGAL_STATE, HttpStatus.BAD_REQUEST)
        register(ErrorCodes.REQUEST_TIMEOUT, HttpStatus.REQUEST_TIMEOUT)
        register(ErrorCodes.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS)
        register(ErrorCodes.DUPLICATE_REQUEST_ID, HttpStatus.BAD_REQUEST)
        register(ErrorCodes.COMMAND_VALIDATION, HttpStatus.BAD_REQUEST)
        register(ErrorCodes.EVENT_VERSION_CONFLICT, HttpStatus.CONFLICT)
        register(ErrorCodes.DUPLICATE_AGGREGATE_ID, HttpStatus.BAD_REQUEST)
        register(ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT, HttpStatus.CONFLICT)
        register(ErrorCodes.SOURCING_VERSION_CONFLICT, HttpStatus.CONFLICT)
        register(ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE, HttpStatus.GONE)
        register(ErrorCodes.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    }

    fun register(errorCode: String, httpStatus: HttpStatus) {
        registrar[errorCode] = httpStatus
    }

    fun getHttpStatus(errorCode: String): HttpStatus? {
        return registrar[errorCode]
    }

    fun ErrorInfo.asHttpStatus(default: HttpStatus = HttpStatus.BAD_REQUEST): HttpStatus {
        return getHttpStatus(this.errorCode) ?: default
    }
}
