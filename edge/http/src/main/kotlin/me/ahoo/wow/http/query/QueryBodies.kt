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

package me.ahoo.wow.http.query

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.json.QueryJson
import tools.jackson.core.JacksonException
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.exc.InvalidFormatException
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.exc.PropertyBindingException
import tools.jackson.databind.node.ObjectNode

/** The operation of a query request, its body's `kind` (decision D127), and the query type its body reads as. */
internal enum class QueryKind(val wire: String, type: Class<*>) {
    LIST("list", ListQuery::class.java),
    PAGED("paged", PagedQuery::class.java),
    CURSOR("cursor", CursorQuery::class.java),
    SINGLE("single", SingleQuery::class.java),
    COUNT("count", CountQuery::class.java),
    AGGREGATE("aggregate", AggregationQuery::class.java),
    ;

    /** Reads a body as the query type, refusing a property the type does not have. */
    val reader: ObjectReader = QueryJson.mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }

        fun of(wire: String): QueryKind? = BY_WIRE[wire]
    }
}

/** A query request's body: its [kind] and the [query] the rest of it reads as. */
internal data class QueryBody(val kind: QueryKind, val query: Any)

/**
 * Reads the body of `POST /{context}/{aggregate}/{model}/query`: a JSON object, its `kind` and the query of that kind
 * (the `:query-api` types in `QueryJson`'s form). A body that cannot be read is a [QueryRequestException] the route
 * answers as `wow.query.malformed`, its binding error naming where and why (the 9.x codes, `INVALID_JSON`,
 * `UNKNOWN_PROPERTY`, ...). Messages the framework wrote (its own `require` checks) pass through; Jackson's are
 * described by kind and JSON path, never quoted.
 */
internal object QueryBodies {
    const val KIND: String = "kind"

    private val KINDS = QueryKind.entries.joinToString(", ") { it.wire }

    fun read(body: ByteArray?): QueryBody {
        val node = objectOf(body)
        val kind = kindOf(node)
        val query = node.deepCopy().also { it.remove(KIND) }
        return QueryBody(kind, decode(kind, query))
    }

    /** [body] as a JSON object. */
    private fun objectOf(body: ByteArray?): ObjectNode {
        if (body == null || body.all { it.toInt().toChar().isWhitespace() }) {
            throw QueryRequestException("Request body is empty.", QueryErrorCodes.EMPTY_BODY)
        }
        return parse(body) as? ObjectNode
            ?: throw QueryRequestException("Request body must be a JSON object.", QueryErrorCodes.BODY_NOT_OBJECT)
    }

    private fun parse(body: ByteArray) = try {
        QueryJson.mapper.readTree(body)
    } catch (invalid: JacksonException) {
        val at = (invalid as? StreamReadException)?.location?.let { " (line ${it.lineNr}, column ${it.columnNr})" }
        throw QueryRequestException(
            "Request body is not valid JSON${at.orEmpty()}.",
            QueryErrorCodes.INVALID_JSON,
            cause = invalid,
        )
    }

    /** The kind [node] names. */
    private fun kindOf(node: ObjectNode): QueryKind {
        val kind = node.get(KIND) ?: throw QueryRequestException(
            "Query body must name its kind: one of $KINDS.",
            QueryErrorCodes.INVALID_VALUE,
            KIND,
        )
        return kind.takeIf { it.isString }?.let { QueryKind.of(it.asString()) }
            ?: throw QueryRequestException(
                "Unknown value [$kind] at [$KIND]; expected one of $KINDS.",
                QueryErrorCodes.UNKNOWN_VALUE,
                KIND,
            )
    }

    /** Decoding a JSON object is a pure function of the client's input, so any failure is the client's. */
    @Suppress("TooGenericExceptionCaught")
    private fun decode(kind: QueryKind, query: ObjectNode): Any = try {
        kind.reader.readValue<Any>(query)
    } catch (error: QueryRequestException) {
        throw error
    } catch (error: RuntimeException) {
        throw error.toQueryBodyError()
    }

    private fun Throwable.toQueryBodyError(): QueryRequestException {
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

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) {
        it.cause.takeIf { cause -> cause !== it }
    }

    /** A `require` of the query types: an [IllegalArgumentException] of its own, with its own text. */
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
}
