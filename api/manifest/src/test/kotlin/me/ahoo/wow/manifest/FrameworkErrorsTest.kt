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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.wire.WireNames
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FrameworkErrorsTest {
    @Test
    fun `the table should give every framework code the status of design 05 section 3`() {
        FrameworkErrors.ALL.associate { it.code to it.status }.assert().isEqualTo(
            mapOf(
                "wow.auth.unauthenticated" to 401,
                "wow.auth.forbidden" to 403,
                "wow.aggregate.forbidden" to 403,
                "wow.request.malformed" to 400,
                "wow.command.malformed" to 400,
                "wow.command.invalid" to 400,
                "wow.wait.invalid" to 400,
                "wow.command.unhandled" to 400,
                "wow.aggregate.not-found" to 404,
                "wow.aggregate.exists" to 409,
                "wow.aggregate.version-mismatch" to 409,
                "wow.command.request-id-reused" to 409,
                "wow.command.undeclared-event" to 500,
                "wow.command.contended" to 409,
                "wow.aggregate.deleted" to 410,
                "wow.command.busy" to 429,
                "wow.command.unknown" to 503,
                "wow.command.failed" to 500,
                "wow.command.refused" to 409,
                "wow.node.closing" to 503,
                "wow.node.standby" to 503,
                "wow.storage.unavailable" to 503,
                "wow.schedule.not-due" to 503,
                "wow.schedule.job-invalid" to 400,
                "wow.ledger.stale" to 409,
                "wow.subscription.parked" to 500,
                "wow.wait.timeout" to 504,
                "wow.route.malformed" to 400,
                "wow.query.malformed" to 400,
                "wow.query.invalid" to 400,
                "wow.query.failed" to 500,
                "wow.query.timeout" to 504,
                "wow.query.busy" to 429,
                "wow.query.not-found" to 404,
                "wow.query.dataset-not-found" to 404,
                "wow.query.too-many-groups" to 400,
                "wow.query.min-version-not-reached" to 409,
                "wow.query.dataset-unavailable" to 409,
                "wow.call.command-forbidden" to 403,
                "wow.peer.unavailable" to 503,
            ),
        )
        FrameworkErrors.DOMAIN_REJECTION_STATUS.assert().isEqualTo(422)
        FrameworkErrors.PRECONDITION_FAILED_STATUS.assert().isEqualTo(412)
    }

    @Test
    fun `every code should be once in the table, with a description and the framework's prefix`() {
        FrameworkErrors.BY_CODE.size.assert().isEqualTo(FrameworkErrors.ALL.size)
        FrameworkErrors.ALL.forEach {
            it.code.assert().startsWith(FrameworkErrors.PREFIX)
            it.description.assert().isNotBlank()
        }
        FrameworkErrors.BY_CODE[FrameworkErrors.AGGREGATE_VERSION_MISMATCH]?.description.assert().contains("412")
    }

    @Test
    fun `every code should have a short title, which a problem answers with`() {
        FrameworkErrors.TITLES.keys.assert().containsExactlyElementsOf(FrameworkErrors.ALL.map { it.code })
        FrameworkErrors.TITLES.values.forEach {
            it.assert().isNotBlank().doesNotEndWith(".")
            it.length.assert().isLessThanOrEqualTo(MAX_TITLE)
        }
    }

    @Test
    fun `a code a retry may get past should say with which request, and wait a second`() {
        FrameworkErrors.ALL.filter { it.retry != Retry.NONE }.map { it.code }.assert().containsExactlyInAnyOrder(
            "wow.command.contended",
            "wow.command.busy",
            "wow.command.unknown",
            "wow.node.closing",
            "wow.node.standby",
            "wow.storage.unavailable",
            "wow.schedule.not-due",
            "wow.wait.timeout",
            "wow.query.min-version-not-reached",
            "wow.query.busy",
            "wow.peer.unavailable",
        )
        FrameworkErrors.ALL.filter { it.retry == Retry.NEW_REQUEST }.map { it.code }.assert()
            .containsExactlyInAnyOrder("wow.query.min-version-not-reached", "wow.query.busy", "wow.peer.unavailable")
        FrameworkErrors.ALL.forEach { error ->
            error.retryAfter.assert().describedAs(error.code)
                .isEqualTo(FrameworkErrors.RETRY_AFTER_SECONDS.takeIf { error.retry != Retry.NONE })
        }
        // A query that ran out of time would run as long again (D254 ③).
        FrameworkErrors.BY_CODE.getValue("wow.query.timeout").retry.assert().isEqualTo(Retry.NONE)
    }

    @Test
    fun `a boundary's problem should be the catalog's of its code`() {
        FrameworkErrors.problemOf("wow.command.busy", "Busy.").apply {
            status.assert().isEqualTo(429)
            retry.assert().isEqualTo(Retry.SAME_REQUEST)
            retryAfter.assert().isEqualTo(1.seconds)
            message.assert().isEqualTo("Busy.")
        }
        FrameworkErrors.problemOf("wow.made.up", "Unknown.").apply {
            status.assert().isEqualTo(FrameworkErrors.INTERNAL_ERROR_STATUS)
            retry.assert().isEqualTo(Retry.NONE)
            retryAfter.assert().isNull()
        }
    }

    @Test
    fun `an error's retry and Retry-After should be given together`() {
        assertThrows<IllegalArgumentException> { ErrorManifest("x.y", 503, retry = Retry.SAME_REQUEST) }
        assertThrows<IllegalArgumentException> { ErrorManifest("x.y", 400, retryAfter = 1) }
        assertThrows<IllegalArgumentException> { ErrorManifest("x.y", 503, retry = Retry.NEW_REQUEST, retryAfter = 0) }
    }

    @Test
    fun `the snapshot the TypeScript client and the reference generate from should be this catalog`() {
        val json = jacksonObjectMapper()
        fun text(value: String) = json.writeValueAsString(value)
        val constants = FrameworkErrors::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.isAccessible = true; it.get(null) as String to it.name }
        val errors = FrameworkErrors.ALL.joinToString(",\n", "[\n", "\n  ]") { error ->
            listOfNotNull(
                "\"name\": ${text(constants.getValue(error.code))}",
                "\"code\": ${text(error.code)}",
                "\"status\": ${error.status}",
                "\"retry\": ${text(WireNames.of(error.retry))}",
                error.retryAfter?.let { "\"retryAfter\": $it" },
                "\"title\": ${text(FrameworkErrors.TITLES.getValue(error.code))}",
                error.description?.let { "\"description\": ${text(it)}" },
            ).joinToString(", ", "    { ", " }")
        }
        val expected = "{\n" +
            "  \"retry\": { \"attempts\": ${FrameworkErrors.RETRY_ATTEMPTS}, " +
            "\"backoffMillis\": ${FrameworkErrors.RETRY_BACKOFF_MILLIS}, " +
            "\"maxWaitSeconds\": ${FrameworkErrors.RETRY_MAX_WAIT_SECONDS} },\n" +
            "  \"errors\": $errors\n}\n"
        val snapshot = Path.of(SNAPSHOT)
        if (System.getenv(UPDATE) != null) {
            snapshot.writeText(expected)
        }
        snapshot.readText().assert()
            .describedAs(
                "Run with $UPDATE=1 to rewrite $SNAPSHOT, then node typescript/wow-client/scripts/framework-errors.mjs " +
                    "and pnpm docs:reference.",
            )
            .isEqualTo(expected)
    }

    private companion object {
        const val MAX_TITLE = 32

        /** Read by typescript/wow-client/scripts/framework-errors.mjs and .github/scripts/docs-reference.mjs. */
        const val SNAPSHOT = "src/test/resources/framework-errors.json"
        const val UPDATE = "WOW_UPDATE_SNAPSHOTS"
    }
}
