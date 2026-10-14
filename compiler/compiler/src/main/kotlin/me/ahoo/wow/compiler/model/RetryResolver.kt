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

package me.ahoo.wow.compiler.model

import com.google.devtools.ksp.symbol.KSAnnotated
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.manifest.RetryManifest
import kotlin.time.Duration

/**
 * A subscription handler's in-process retry budget as `@Retry` declares it (roadmap P.3): each setting null when it
 * is not given. A handler's settings are its own `@Retry`'s, each one it does not give its processor's ([over]).
 */
internal data class RetryBinding(
    val maxAttempts: Int? = null,
    val initialBackoff: Duration? = null,
    val maxBackoff: Duration? = null,
    val timeout: Duration? = null,
) {
    /** Whether no setting is given. */
    val isEmpty: Boolean get() = this == RetryBinding()

    /** These settings, each one not given being [base]'s. */
    fun over(base: RetryBinding?): RetryBinding = if (base == null) {
        this
    } else {
        RetryBinding(
            maxAttempts ?: base.maxAttempts,
            initialBackoff ?: base.initialBackoff,
            maxBackoff ?: base.maxBackoff,
            timeout ?: base.timeout,
        )
    }

    /** The kernel's `HandlerRetry` of these settings, as the generated binding writes it. */
    fun source(): String =
        "me.ahoo.wow.kernel.HandlerRetry.of(${maxAttempts ?: 0}, \"${iso(initialBackoff)}\", " +
            "\"${iso(maxBackoff)}\", \"${iso(timeout)}\")"

    /** These settings as the Manifest's subscription entry records them, durations in ISO-8601. */
    fun manifest(): RetryManifest = RetryManifest(
        maxAttempts = maxAttempts,
        initialBackoff = initialBackoff?.toIsoString(),
        maxBackoff = maxBackoff?.toIsoString(),
        timeout = timeout?.toIsoString(),
    )

    private fun iso(duration: Duration?): String = duration?.toIsoString().orEmpty()
}

/**
 * Reads `@Retry` (roadmap P.3) off a processor class or a handler function, and checks it: [MAX_ATTEMPTS] is at least
 * 1, or 0 for not given; each duration is blank (not given) or a Kotlin (`500ms`, `2s`) or ISO-8601 (`PT2S`) duration,
 * a backoff not negative and a timeout positive; an attribute only 9.x's `@Retry` has is an error.
 */
internal object RetryResolver {
    private val RETRY = Retry::class.java.name
    private const val MAX_ATTEMPTS = "maxAttempts"
    private const val INITIAL_BACKOFF = "initialBackoff"
    private const val MAX_BACKOFF = "maxBackoff"
    private const val TIMEOUT = "timeout"
    private val SETTINGS = setOf(MAX_ATTEMPTS, INITIAL_BACKOFF, MAX_BACKOFF, TIMEOUT)

    /** The settings of the `@Retry` on [annotated], described as [where]; null without one, or when it gives none. */
    fun read(annotated: KSAnnotated, where: String, problems: MutableList<String>): RetryBinding? {
        val annotation = annotated.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == RETRY
        } ?: return null
        val arguments = annotation.arguments.associate { it.name?.asString() to it.value }
        arguments.keys.filterNotNull().filterNot { it in SETTINGS }.sorted().forEach {
            problems += "$where is annotated @Retry($it), a 9.x setting V10 does not have: V10's @Retry takes " +
                "${SETTINGS.joinToString()}; ${V10Annotations.MIGRATION}"
        }
        val maxAttempts = (arguments[MAX_ATTEMPTS] as? Int) ?: 0
        if (maxAttempts < 0) {
            problems += "$where is annotated @Retry(maxAttempts = $maxAttempts): a call is attempted at least once, " +
                "or 0 for not given"
        }
        return RetryBinding(
            maxAttempts = maxAttempts.takeIf { it > 0 },
            initialBackoff = duration(where, INITIAL_BACKOFF, arguments, problems, positive = false),
            maxBackoff = duration(where, MAX_BACKOFF, arguments, problems, positive = false),
            timeout = duration(where, TIMEOUT, arguments, problems, positive = true),
        ).takeUnless { it.isEmpty }
    }

    private fun duration(
        where: String,
        setting: String,
        arguments: Map<String?, Any?>,
        problems: MutableList<String>,
        positive: Boolean,
    ): Duration? {
        val text = (arguments[setting] as? String).orEmpty()
        if (text.isBlank()) {
            return null
        }
        val duration = Duration.parseOrNull(text.trim())
        val problem = when {
            duration == null -> "is a duration such as 500ms, 2s or PT2S"
            positive && !duration.isPositive() -> "is positive"
            duration.isNegative() -> "is never negative"
            else -> return duration
        }
        problems += "$where is annotated @Retry($setting = \"$text\"): it $problem"
        return null
    }
}
