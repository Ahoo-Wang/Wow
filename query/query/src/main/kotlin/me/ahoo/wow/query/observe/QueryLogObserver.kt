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
package me.ahoo.wow.query.observe

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.state.InvalidStateReadException
import me.ahoo.wow.query.state.StateNotFoundException

/**
 * Logs every failed query by who must act on it:
 * - a rejection the caller can fix (an error-catalog violation, a missing caller scope, a state read's parameter) at
 *   DEBUG, by its message alone: it is the client's error, answered 4xx, and logging it louder lets callers flood the
 *   log; so is a state read answered as not found, whatever the reason, which the audit keeps;
 * - a storage that cannot be reached ([QueryStorageUnavailableException], answered 503 with `Retry-After`) at WARN, by
 *   its message and its redacted cause chain: an outage, not a defect, and every query meets it until it ends;
 * - a server fault the core states ([QueryExecutionException]) at ERROR, by its message and its cause chain with
 *   every message redacted: a cause can hold record values (a failing mask strategy sees the raw value, a storage
 *   driver echoes the query), so only each cause's class name and stack trace reach the log;
 * - any other failure at ERROR with its stack trace.
 */
@InternalWowApi
class QueryLogObserver : QueryObserver {
    @InternalWowApi
    companion object {
        private val log = KotlinLogging.logger { }
    }

    override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) {
        when {
            error.isRejection() -> log.debug { "Query [$aggregateType/$queryType] rejected: ${error.message}" }

            error is QueryStorageUnavailableException -> {
                val cause = error.cause
                if (cause == null) log.warn { error.message } else log.warn(cause.redacted()) { error.message }
            }

            error is QueryExecutionException -> {
                val cause = error.cause
                if (cause == null) log.error { error.message } else log.error(cause.redacted()) { error.message }
            }
            else -> log.error(error) { error.message }
        }
    }
}

/** A failure the caller can fix, or a state it may not read: answered 4xx. */
private fun Throwable.isRejection(): Boolean =
    this is QuerySchemaValidationException ||
        this is QueryRequestException ||
        this is InvalidStateReadException ||
        this is StateNotFoundException ||
        isMissingScope()

/**
 * A missing caller scope, whichever component rejected it ([QueryScopeRequiredException], an ABAC policy that requires
 * principal tags): answered 403, so it is the caller's to fix.
 */
private fun Throwable.isMissingScope(): Boolean =
    this is QueryError && errorCode == QueryErrors.FORBIDDEN

/** A message-free copy of a cause: its class name and stack trace, the same for its own causes. */
internal fun Throwable.redacted(depth: Int = 0): Throwable {
    val next = cause?.takeIf { it !== this && depth < MAX_REDACTED_CAUSES }
    // The cause goes through the constructor: a throwable constructed with a cause, even `null`, rejects initCause.
    return RedactedCause(javaClass.name, next?.redacted(depth + 1)).also { copy -> copy.stackTrace = stackTrace }
}

private const val MAX_REDACTED_CAUSES = 16

private class RedactedCause(type: String, cause: Throwable?) : RuntimeException(type, cause, false, true)
