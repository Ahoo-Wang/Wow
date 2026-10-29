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

import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import org.springframework.http.HttpStatus

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
internal class ErrorStatuses(manifest: Manifest, private val overrides: Map<String, Int> = emptyMap()) {
    private val framework: Map<String, ErrorManifest> =
        FrameworkErrors.BY_CODE + manifest.errors.associateBy { it.code }
    private val byContext: Map<String, Map<String, ErrorManifest>> =
        manifest.contexts.associate { context -> context.name to context.errors.associateBy { it.code } }

    /** The status of [code] for a command of [context]; [ifMatch] when the request carried `If-Match`. */
    fun status(code: String, context: String? = null, ifMatch: Boolean = false): Int {
        overrides[code]?.let { return it }
        if (ifMatch && code == FrameworkErrors.AGGREGATE_VERSION_MISMATCH) {
            return FrameworkErrors.PRECONDITION_FAILED_STATUS
        }
        return find(code, context)?.status
            ?: if (code.startsWith(FrameworkErrors.PREFIX)) {
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
            extensions = error.extensions,
            // The version a rejection answered: its events', else the one the decision saw, the next If-Match.
            version = result.version.takeIf { result.status == CommandResult.Status.REJECTED },
        )
    }

    /** A problem with [code] before the command has a ref. */
    fun problemOf(code: String, detail: String, context: String? = null): Problem {
        val status = status(code, context)
        return Problem(status, title(code, context, status), detail, code)
    }

    private fun find(code: String, context: String?): ErrorManifest? =
        context?.let { byContext[it]?.get(code) } ?: framework[code]
}
