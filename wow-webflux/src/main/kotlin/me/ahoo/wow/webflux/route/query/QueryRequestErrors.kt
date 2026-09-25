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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.exception.ErrorCodes
import org.springframework.core.codec.DecodingException
import tools.jackson.core.JacksonException
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.exc.InvalidFormatException
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.exc.PropertyBindingException

/*
 * One policy for every query route: a request the client got wrong is a 400 `IllegalArgument` whose message says what
 * is wrong, in the framework's words. Messages the framework authored (its own `require` checks) pass through; Jackson's
 * structural failures are described by kind and JSON path; anything else (JDK or library exception text, class names)
 * never reaches the client.
 */

/**
 * A query request the client got wrong: `IllegalArgument` with the human [errorMsg] and one binding error naming where
 * ([name], a JSON path or `body`) and which rule ([code], stable and machine-readable).
 */
class QueryRequestException(
    override val errorMsg: String,
    val code: String,
    name: String = BODY,
    cause: Throwable? = null,
) : IllegalArgumentException(errorMsg, cause), ErrorInfo {
    override val errorCode: String
        get() = ErrorCodes.ILLEGAL_ARGUMENT
    override val bindingErrors: List<BindingError> = listOf(BindingError(name, errorMsg, code))

    companion object {
        const val BODY = "body"
    }
}

/** A failure to read the HTTP body as a JSON object at all. */
internal fun DecodingException.toQueryBodyReadError(): QueryRequestException {
    val jackson = causes().filterIsInstance<JacksonException>().firstOrNull()
    return when {
        jackson is MismatchedInputException && jackson.originalMessage.orEmpty().startsWith(NO_CONTENT) ->
            QueryRequestException("Request body is empty.", QueryErrorCodes.EMPTY_BODY, cause = this)
        jackson is MismatchedInputException ->
            QueryRequestException(
                "Request body must be a JSON object.",
                QueryErrorCodes.BODY_NOT_OBJECT,
                cause = this
            )
        else -> QueryRequestException(
            (jackson as? StreamReadException)?.location?.let {
                "Request body is not valid JSON (line ${it.lineNr}, column ${it.columnNr})."
            } ?: "Request body is not valid JSON.",
            QueryErrorCodes.INVALID_JSON,
            cause = this,
        )
    }
}

/** A failure to decode the JSON object as the route's query type. */
internal fun Throwable.toQueryBodyError(): QueryRequestException {
    val jackson = causes().filterIsInstance<JacksonException>().firstOrNull()
    val path = jackson?.jsonPath()
    val name = path ?: QueryRequestException.BODY
    val authored = causes().firstOrNull { it.isFrameworkAuthored() }
    if (authored != null) {
        return QueryRequestException(checkNotNull(authored.message), QueryErrorCodes.INVALID_REQUEST, name, this)
    }
    val at = path?.let { " at [$it]" }.orEmpty()
    val (message, code) = when (jackson) {
        is InvalidFormatException if jackson.targetType?.isEnum == true ->
            "Unknown value [${jackson.value}]$at; expected one of " +
                "${jackson.targetType.enumConstants.joinToString(", ")}." to QueryErrorCodes.UNKNOWN_VALUE
        is PropertyBindingException ->
            "Unknown property [${jackson.propertyName}]$at." to QueryErrorCodes.UNKNOWN_PROPERTY
        is InvalidTypeIdException -> "Unknown type [${jackson.typeId}]$at." to QueryErrorCodes.UNKNOWN_TYPE
        is MismatchedInputException -> "Invalid or missing value$at." to QueryErrorCodes.INVALID_VALUE
        else -> "Invalid query request body$at." to QueryErrorCodes.INVALID_REQUEST
    }
    return QueryRequestException(message, code, name, this)
}

private const val NO_CONTENT = "No content to map"

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) {
    it.cause.takeIf { cause -> cause !== it }
}

/**
 * The framework's own argument checks throw plain [IllegalArgumentException]s whose text is written for callers.
 * Subclasses (e.g. `NumberFormatException`) and `Enum.valueOf` failures carry JDK text and are not.
 */
private fun Throwable.isFrameworkAuthored(): Boolean =
    javaClass == IllegalArgumentException::class.java &&
        !message.isNullOrBlank() &&
        !message!!.startsWith("No enum constant")

private fun JacksonException.jsonPath(): String? = path.takeIf { it.isNotEmpty() }?.joinToString("") { reference ->
    when {
        reference.propertyName != null -> ".${reference.propertyName}"
        reference.index >= 0 -> "[${reference.index}]"
        else -> ""
    }
}?.removePrefix(".")?.takeIf { it.isNotEmpty() }
