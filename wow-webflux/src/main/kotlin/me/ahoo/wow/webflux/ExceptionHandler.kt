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

package me.ahoo.wow.webflux

import me.ahoo.wow.api.exception.ConflictException
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.GoneException
import me.ahoo.wow.api.exception.NotFoundException
import me.ahoo.wow.api.exception.PreconditionFailedException
import me.ahoo.wow.api.exception.PreconditionRequiredException
import me.ahoo.wow.api.exception.asErrorInfo
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.webflux.route.asServerResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.util.concurrent.TimeoutException

interface ExceptionHandler {
    fun handle(throwable: Throwable): Mono<ServerResponse>
}

object DefaultExceptionHandler : ExceptionHandler {
    fun Throwable.asHttpStatus(): HttpStatus {
        return when (this) {
            is IllegalArgumentException -> HttpStatus.PRECONDITION_FAILED
            is IllegalStateException -> HttpStatus.PRECONDITION_REQUIRED
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ConflictException -> HttpStatus.CONFLICT
            is GoneException -> HttpStatus.GONE
            is PreconditionFailedException -> HttpStatus.PRECONDITION_FAILED
            is PreconditionRequiredException -> HttpStatus.PRECONDITION_REQUIRED
            is TimeoutException -> HttpStatus.GATEWAY_TIMEOUT
            else -> HttpStatus.BAD_REQUEST
        }
    }

    fun Throwable.asResponseEntity(): ResponseEntity<ErrorInfo> {
        return ResponseEntity.status(asHttpStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(asErrorInfo())
    }

    override fun handle(throwable: Throwable): Mono<ServerResponse> {
        if (throwable is CommandResultException) {
            return throwable.commandResult.asServerResponse()
        }
        val status: HttpStatus = throwable.asHttpStatus()
        return ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(throwable.asErrorInfo())
    }
}
