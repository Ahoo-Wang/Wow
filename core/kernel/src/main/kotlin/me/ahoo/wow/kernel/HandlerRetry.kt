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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.spi.subscription.FailureClass
import kotlin.time.Duration

/**
 * A subscription's own in-process retry budget: the settings it gives, each null when it gives none,
 * which then come from the budget below it ([over]). A handler's `@Retry` gives one ([of]), the host's
 * `wow.subscriptions.[<context>.<name>].retry.*` another, over it; both fall back to `wow.subscription.retry`.
 *
 * - [maxAttempts]: the calls in all, at least 1.
 * - [initialBackoff]: the wait after the first failure; [maxBackoff]: the longest wait; [multiplier]: how much
 *   longer each next wait is.
 * - [timeout]: how long one attempt may run before it is cancelled and counted as failed.
 * - [recoverable], [unrecoverable]: the exception classes the handler classifies itself,
 *   each with its subclasses ([classify]); null when not given, never empty.
 */
class HandlerRetry @InternalWowApi constructor(
    val maxAttempts: Int? = null,
    val initialBackoff: Duration? = null,
    val maxBackoff: Duration? = null,
    val multiplier: Double? = null,
    val timeout: Duration? = null,
    val recoverable: List<Class<out Throwable>>? = null,
    val unrecoverable: List<Class<out Throwable>>? = null,
) {
    init {
        require(
            maxAttempts == null || maxAttempts >= 1
        ) { "A call is attempted at least once, not $maxAttempts times." }
        require(initialBackoff == null || !initialBackoff.isNegative()) { "A backoff is never negative." }
        require(maxBackoff == null || !maxBackoff.isNegative()) { "A backoff is never negative." }
        require(multiplier == null || multiplier >= 1.0) { "A backoff never shrinks." }
        require(timeout == null || timeout.isPositive()) { "The timeout of an attempt is positive." }
        require(recoverable?.isEmpty() != true && unrecoverable?.isEmpty() != true) {
            "An exception class list that is given holds at least one class."
        }
    }

    /** Whether no setting is given. */
    val isEmpty: Boolean
        get() = this == NONE

    /** These settings, each one not given being [base]'s. */
    fun over(base: HandlerRetry?): HandlerRetry = if (base == null) {
        this
    } else {
        HandlerRetry(
            maxAttempts ?: base.maxAttempts,
            initialBackoff ?: base.initialBackoff,
            maxBackoff ?: base.maxBackoff,
            multiplier ?: base.multiplier,
            timeout ?: base.timeout,
            recoverable ?: base.recoverable,
            unrecoverable ?: base.unrecoverable,
        )
    }

    /**
     * The class of [failure] by the handler's own lists (9.x `Retry.recoverable`): [FailureClass.UNRECOVERABLE] when
     * an [unrecoverable] class is nearer to it in its class hierarchy than every [recoverable] one,
     * [FailureClass.RECOVERABLE] when a [recoverable] class is at least as near; null when neither list holds a class
     * it is an instance of.
     */
    fun classify(failure: Throwable): FailureClass? {
        val recoverableDistance = recoverable.nearest(failure.javaClass)
        val unrecoverableDistance = unrecoverable.nearest(failure.javaClass)
        return when {
            unrecoverableDistance != null && (recoverableDistance == null || unrecoverableDistance < recoverableDistance) ->
                FailureClass.UNRECOVERABLE
            recoverableDistance != null -> FailureClass.RECOVERABLE
            else -> null
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is HandlerRetry && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "HandlerRetry(" +
        "maxAttempts=$maxAttempts, initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, multiplier=$multiplier, timeout=$timeout, recoverable=$recoverable, unrecoverable=$unrecoverable)"

    private fun valueFields(): List<Any?> = listOf(
        maxAttempts,
        initialBackoff,
        maxBackoff,
        multiplier,
        timeout,
        recoverable,
        unrecoverable
    )

    companion object {
        /** No setting given. */
        @JvmField
        val NONE: HandlerRetry = HandlerRetry()

        /** The attempts of a handler whose budget gives none, the runtime's `wow.subscription.retry.max-attempts`. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 5

        /**
         * The budget of the `@Retry` settings given: a [maxAttempts] of 0 and blank durations are not given;
         * durations are Kotlin's (`500ms`, `1m 30s`) or ISO-8601 (`PT2S`). Null when none is given.
         */
        @JvmStatic
        fun of(
            maxAttempts: Int = 0,
            initialBackoff: String = "",
            maxBackoff: String = "",
            timeout: String = "",
            multiplier: Double = 0.0,
        ): HandlerRetry? = of(maxAttempts, initialBackoff, maxBackoff, timeout, emptyList(), emptyList(), multiplier)

        /**
         * The budget of the `@Retry` settings given, as [of] without the exception classes, and the classes of
         * `@Retry(recoverable, unrecoverable)`: an empty list is not given.
         */
        @JvmStatic
        fun of(
            maxAttempts: Int,
            initialBackoff: String,
            maxBackoff: String,
            timeout: String,
            recoverable: List<Class<out Throwable>>,
            unrecoverable: List<Class<out Throwable>>,
            multiplier: Double = 0.0,
        ): HandlerRetry? {
            require(maxAttempts >= 0) { "@Retry(maxAttempts) is at least 1, or 0 for not given; not $maxAttempts." }
            require(multiplier == 0.0 || multiplier >= 1.0) {
                "@Retry(multiplier) is at least 1, or 0 for not given; not $multiplier."
            }
            return HandlerRetry(
                maxAttempts = maxAttempts.takeIf { it > 0 },
                initialBackoff = duration("initialBackoff", initialBackoff),
                maxBackoff = duration("maxBackoff", maxBackoff),
                multiplier = multiplier.takeIf { it > 0.0 },
                timeout = duration("timeout", timeout),
                recoverable = recoverable.takeUnless { it.isEmpty() },
                unrecoverable = unrecoverable.takeUnless { it.isEmpty() },
            ).takeUnless { it.isEmpty }
        }

        /** The budget [retry] gives; null when it is absent or gives nothing. */
        @JvmStatic
        fun of(retry: Retry?): HandlerRetry? = retry?.let {
            of(
                it.maxAttempts,
                it.initialBackoff,
                it.maxBackoff,
                it.timeout,
                it.recoverable.map { type -> type.java },
                it.unrecoverable.map { type -> type.java },
                it.multiplier,
            )
        }

        /**
         * The budget of a handler annotated [function] in a processor annotated [processor]: the handler's settings,
         * each one it does not give being the processor's. Null when neither gives any.
         */
        @JvmStatic
        fun of(function: Retry?, processor: Retry?): HandlerRetry? {
            val own = of(function)
            val inherited = of(processor)
            return own?.over(inherited) ?: inherited
        }

        private fun duration(setting: String, text: String): Duration? {
            if (text.isBlank()) {
                return null
            }
            return requireNotNull(Duration.parseOrNull(text.trim())) {
                "@Retry($setting) is a duration such as 500ms, 2s or PT2S, not \"$text\"."
            }
        }
    }
}

/** How many superclasses up from [type] the nearest of these classes is, null when [type] is an instance of none. */
private fun List<Class<out Throwable>>?.nearest(type: Class<out Throwable>): Int? =
    this?.mapNotNull { listed ->
        generateSequence<Class<*>>(type) { it.superclass }.indexOf(listed).takeIf { it >= 0 }
    }?.minOrNull()
