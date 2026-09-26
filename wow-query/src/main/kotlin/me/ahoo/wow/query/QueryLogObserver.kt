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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QuerySchemaValidationException

/**
 * Logs every failed query by who must act on it:
 * - a rejection the caller can fix (an error-catalog violation, a missing authenticated scope) at DEBUG, by its
 *   message alone: it is the client's error, answered 4xx, and logging it louder lets callers flood the log;
 * - a server fault the core states ([QueryExecutionException]) at ERROR, by its message and its cause chain with
 *   every message redacted: a cause can hold record values (a failing mask strategy sees the raw value, a storage
 *   driver echoes the query), so only each cause's class name and stack trace reach the log;
 * - any other failure at ERROR with its stack trace.
 */
class QueryLogObserver : QueryObserver {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    override fun onError(namedAggregate: NamedAggregate, queryType: QueryType, error: Throwable) {
        when (error) {
            is QuerySchemaValidationException, is QueryRequestException, is QueryScopeRequiredException ->
                log.debug { "Query [$namedAggregate/$queryType] rejected: ${error.message}" }

            is QueryExecutionException -> {
                val cause = error.cause
                if (cause == null) log.error { error.message } else log.error(cause.redacted()) { error.message }
            }
            else -> log.error(error) { error.message }
        }
    }
}

/** A message-free copy of a cause: its class name and stack trace, the same for its own causes. */
internal fun Throwable.redacted(depth: Int = 0): Throwable = RedactedCause(javaClass.name).also { copy ->
    copy.stackTrace = stackTrace
    val next = cause
    if (next != null && next !== this && depth < MAX_REDACTED_CAUSES) copy.initCause(next.redacted(depth + 1))
}

private const val MAX_REDACTED_CAUSES = 16

private class RedactedCause(type: String) : RuntimeException(type, null, false, true)
