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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.DefaultErrorInfo
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.CommandValidationException
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.rest.WowHeaders.ERROR_CODE
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.route.response.errorResume
import me.ahoo.wow.webflux.route.toBatchResult
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.core.codec.DecodingException
import org.springframework.http.HttpStatus
import org.springframework.http.codec.ServerSentEvent
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RequestPredicates.GET
import org.springframework.web.reactive.function.server.RouterFunctions.route
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import java.io.FileNotFoundException
import java.util.concurrent.TimeoutException

/**
 * One failure, one error, whatever the route answers with: the error response of a JSON route, the error event of an
 * SSE stream and the error of a batch result all come from the same [WebFluxErrorStrategy] mapping. A failure the
 * mapping does not classify is `InternalServerError` with a generic message on all three (since 9.3.0; before, the
 * SSE event and the batch result said `BadRequest` with the exception's own message).
 */
class ErrorCodeGoldenTableTest {
    data class Row(
        val failure: Throwable,
        val errorCode: String,
        val status: HttpStatus,
        val errorMsg: String,
        val bindingErrors: List<BindingError> = emptyList(),
    ) {
        override fun toString(): String = "${failure.javaClass.simpleName} -> $errorCode $status"
    }

    @ParameterizedTest
    @MethodSource("rows")
    fun `a JSON route answers with the row's status, code and message`(row: Row) {
        WebTestClient.bindToRouterFunction(
            route(GET("/failure")) { request -> handler.handle(request, row.failure) }
        ).build()
            .get().uri("/failure")
            .exchange()
            .expectStatus().isEqualTo(row.status)
            .expectHeader().valueEquals(ERROR_CODE, row.errorCode)
            .expectBody(String::class.java)
            .consumeWith {
                it.responseBody!!.toErrorInfo().assert().isEqualTo(row.errorInfo())
            }
    }

    @ParameterizedTest
    @MethodSource("rows")
    fun `an SSE stream ends with an error event of the row's code and message`(row: Row) {
        Flux.error<ServerSentEvent<String>>(row.failure)
            .errorResume(request, handler)
            .test()
            .consumeNextWith {
                it.event().assert().isEqualTo(row.errorCode)
                it.data()!!.toErrorInfo().assert().isEqualTo(row.errorInfo())
            }
            .expectErrorSatisfies { it.assert().isSameAs(row.failure) }
            .verify()
    }

    @ParameterizedTest
    @MethodSource("rows")
    fun `a batch result carries the row's code and message`(row: Row) {
        Flux.error<AggregateId>(row.failure)
            .toBatchResult("(0)", request, handler)
            .test()
            .consumeNextWith {
                // A batch result carries no binding errors.
                ErrorInfo.of(it.errorCode, it.errorMsg).assert().isEqualTo(ErrorInfo.of(row.errorCode, row.errorMsg))
            }
            .verifyComplete()
    }

    companion object {
        private val handler = WebFluxRequestExceptionHandler()
        private val request = MockServerRequest.builder().build()
        private const val UNEXPECTED = "Unexpected server error"

        private fun Row.errorInfo(): ErrorInfo = ErrorInfo.of(errorCode, errorMsg, bindingErrors)

        private fun String.toErrorInfo(): ErrorInfo = toObject<DefaultErrorInfo>()

        @JvmStatic
        fun rows(): List<Row> {
            val batchTask = BatchTaskException(
                MOCK_AGGREGATE_METADATA.aggregateId("id1"),
                RuntimeException("driver detail")
            )
            val bindingErrors = listOf(BindingError("quantity", "must be positive"))
            return listOf(
                Row(
                    WowException("OrderNotPaid", "order not paid"),
                    "OrderNotPaid",
                    HttpStatus.BAD_REQUEST,
                    "order not paid"
                ),
                Row(
                    CommandValidationException(Any(), "invalid command", bindingErrors),
                    ErrorCodes.COMMAND_VALIDATION,
                    HttpStatus.BAD_REQUEST,
                    "invalid command",
                    bindingErrors
                ),
                Row(
                    IllegalArgumentException("bad argument"),
                    ErrorCodes.ILLEGAL_ARGUMENT,
                    HttpStatus.BAD_REQUEST,
                    "bad argument"
                ),
                Row(IllegalStateException("bad state"), ErrorCodes.ILLEGAL_STATE, HttpStatus.BAD_REQUEST, "bad state"),
                Row(TimeoutException("too slow"), ErrorCodes.REQUEST_TIMEOUT, HttpStatus.REQUEST_TIMEOUT, "too slow"),
                Row(FileNotFoundException("missing"), ErrorCodes.NOT_FOUND, HttpStatus.NOT_FOUND, "missing"),
                Row(NotFoundResourceException("no view"), ErrorCodes.NOT_FOUND, HttpStatus.NOT_FOUND, "no view"),
                Row(batchTask, BatchTaskException.ERROR_CODE, HttpStatus.BAD_REQUEST, batchTask.message!!),
                Row(
                    NullPointerException("secret npe"),
                    ErrorCodes.INTERNAL_SERVER_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    UNEXPECTED
                ),
                Row(
                    RuntimeException("secret driver detail"),
                    ErrorCodes.INTERNAL_SERVER_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    UNEXPECTED
                ),
                Row(
                    DecodingException("Malformed JSON"),
                    ErrorCodes.INTERNAL_SERVER_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    UNEXPECTED
                ),
            )
        }
    }
}
