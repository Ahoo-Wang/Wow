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

package me.ahoo.wow.http.problem

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import org.springframework.http.HttpStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * The HTTP status of each error code, from the Manifest's tables:
 *
 * 1. a status the host configures for the code ([overrides]) wins;
 * 2. then the table of the context the command was sent to (its domain rejection codes);
 * 3. then the Manifest's framework table, and [FrameworkErrors.ALL] for a Manifest that lists none;
 * 4. else a domain rejection is 422 ([FrameworkErrors.DOMAIN_REJECTION_STATUS]) and an unknown framework code
 *    (`wow.*`) 500.
 *
 * `wow.aggregate.version-mismatch` is 412 instead of its table status when the request carried `If-Match`. What a
 * retry may do, and its `Retry-After`, the problem takes from the error catalog ([Problem.retry]).
 */
@InternalWowApi
class ErrorStatuses(manifest: Manifest, private val overrides: Map<String, Int> = emptyMap()) {
    private val framework: Map<String, ErrorManifest> =
        FrameworkErrors.BY_CODE + manifest.errors.associateBy { it.code }
    private val byContext: Map<String, Map<String, ErrorManifest>> =
        manifest.contexts.associate { context -> context.name to context.errors.associateBy { it.code } }

    /** The kept names a domain's extension took that this node warned of: one warning per name, so a few at most. */
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The status the host configures for [code] (`wow.http.errors.[<code>].status`), or null when it configures none. */
    fun configured(code: String): Int? = overrides[code]

    /** The status of [code] for a command of [context]; [ifMatch] when the request carried `If-Match`. */
    fun status(code: String, context: String? = null, ifMatch: Boolean = false): Int {
        overrides[code]?.let { return it }
        if (ifMatch && code == FrameworkCodes.AGGREGATE_VERSION_MISMATCH) {
            return FrameworkErrors.PRECONDITION_FAILED_STATUS
        }
        return find(code, context)?.status
            ?: if (code.startsWith(FrameworkCodes.PREFIX)) {
                HttpStatus.INTERNAL_SERVER_ERROR.value()
            } else {
                FrameworkErrors.DOMAIN_REJECTION_STATUS
            }
    }

    /**
     * The title of a problem with [code] and [status], the same for every occurrence of the code (RFC 9457): a
     * framework code's short title ([FrameworkErrors.TITLES]), a domain code's description in its context's table,
     * else the reason phrase. The request's own account is the problem's `detail`.
     */
    fun title(code: String, context: String?, status: Int): String =
        FrameworkErrors.TITLES[code]
            ?: context?.let { byContext[it]?.get(code)?.description }
            ?: HttpStatus.resolve(status)?.reasonPhrase
            ?: code

    /** The problem of [result], a command that did not succeed. */
    fun problemOf(result: CommandResult, ifMatch: Boolean = false): Problem {
        val error = checkNotNull(result.error) { "Only a result that did not succeed is a problem." }
        val context = result.ref.type.context
        val status = status(error.code, context, ifMatch)
        return Problem(
            status = status,
            title = title(error.code, context, status),
            detail = error.message,
            code = error.code,
            violations = error.violations,
            result = result,
            extensions = domainExtensions(error.extensions, error.code, KEPT_IN_RESULT),
            // The version a rejection answered: its events', else the one the decision saw, the next If-Match.
            version = result.version.takeIf { result.status == CommandResult.Status.REJECTED },
        )
    }

    /**
     * The problem of [failure], answered as it is wherever it is thrown (an application route, a service route relaying
     * another service's answer): its status, its code and its result, what a retry may do with its `Retry-After`,
     * in whole seconds rounded up, as the failure says, not as the catalog would for its code, which may not even know
     * it, and its violations, else its result's. Its title is the code's in [context].
     */
    fun problemOf(failure: WowProblemException, context: String? = null): Problem {
        val answered = failure.result?.let(::problemOf)
            ?: Problem(failure.status, title(failure.code, context, failure.status), failure.message, failure.code)
        return answered.copyWith(
            status = failure.status,
            retry = failure.retry,
            retryAfter = failure.retryAfter,
            violations = failure.violations.ifEmpty { answered.violations },
        )
    }

    /** A problem with [code] before the command has a ref. */
    fun problemOf(code: String, detail: String, context: String? = null): Problem {
        val status = status(code, context)
        return Problem(status, title(code, context, status), detail, code)
    }

    /**
     * A domain's [extensions], a rejection's or a service route refusal's, without the names a problem writes or Wow
     * keeps ([Problem.domainExtensions]). The first time this node leaves out a name with a value, it warns once of that
     * name: the name and [code], never the value, and where the value still is ([kept]). A developer's own data is not
     * dropped without a word, and a name that recurs on every request is not logged on each.
     */
    internal fun domainExtensions(extensions: Map<String, Any?>, code: String, kept: String): Map<String, Any?> {
        val answered = Problem.domainExtensions(extensions)
        extensions.filter { (name, value) -> value != null && name !in answered && warned.add(name) }.keys
            .forEach { name ->
                logger.warn {
                    "The extension '$name' of $code is left out of its problem: a problem keeps the name '$name' for " +
                        "a member of its own. $kept Rename the extension to have it answered."
                }
            }
        return answered
    }

    private fun find(code: String, context: String?): ErrorManifest? =
        context?.let { byContext[it]?.get(code) } ?: framework[code]

    internal companion object {
        private val logger = KotlinLogging.logger {}

        /** Where a rejection's extension left out of its problem still is. */
        const val KEPT_IN_RESULT: String = "The command's result still carries it, in its error's extensions."

        /** Where a service route refusal's extension left out of its problem still is: nowhere it answers. */
        const val KEPT_NOWHERE: String = "The refusal is answered without it."
    }
}
