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

package me.ahoo.wow.spring.boot.node

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** The runtime's own timer defaults, which these keys default to. */
private val DEFAULT_TIMERS = TimerOptions()

/**
 * The node's firing loop of the timers, which sends the scheduled commands when they are due.
 */
@InternalWowApi
class TimersProperties {
    /**
     * How often the loop reads the timers due within `lookahead`, with one query per timer store over every
     * partition the node owns; a timer is sent up to this late.
     */
    var pollInterval: Duration = DEFAULT_TIMERS.pollInterval.toJavaDuration()

    /** How far ahead each read loads the timers that fall due; a longer lookahead reads more timers at once. */
    var lookahead: Duration = DEFAULT_TIMERS.lookahead.toJavaDuration()

    /**
     * The attempts a technical failure to send a timer gets before the timer is parked, under the subscription
     * `wow.timers.sends`.
     */
    var maxAttempts: Int = DEFAULT_TIMERS.retry.maxAttempts

    /**
     * How late a timer may be sent, after an outage, before it is cancelled with the outcome `EXPIRED` instead;
     * not set, every timer is sent however late.
     */
    var expiresAfter: Duration? = null

    /** The most commands of timers a partition sends at once. */
    var parallelism: Int = DEFAULT_TIMERS.parallelism

    /**
     * The most commands of timers a partition sends a second, so a backlog of overdue timers drains at that rate;
     * not set, as fast as `parallelism` allows.
     */
    var maxFireRate: Double? = null

    /**
     * How long the claim on a timer being sent holds: when its node dies, the next owner of the partition sends
     * the timer again after it. Longer than a send takes.
     */
    var claimTimeout: Duration = DEFAULT_TIMERS.claimTimeout.toJavaDuration()

    /**
     * How long a scheduled command waits in its target for its due time on the target node's clock; one that
     * arrives earlier than that fails and is sent again later.
     */
    var maxEarlyWait: Duration = DEFAULT_TIMERS.maxEarlyWait.toJavaDuration()

    fun options(): TimerOptions = DEFAULT_TIMERS.copyWith(
        pollInterval = pollInterval.toKotlinDuration(),
        lookahead = lookahead.toKotlinDuration(),
        retry = DEFAULT_TIMERS.retry.copyWith(maxAttempts = maxAttempts),
        expiresAfter = expiresAfter?.toKotlinDuration(),
        parallelism = parallelism,
        maxFireRate = maxFireRate,
        claimTimeout = claimTimeout.toKotlinDuration(),
        maxEarlyWait = maxEarlyWait.toKotlinDuration(),
    )
}

/**
 * An `@OnSchedule` job's settings, the only place it is configured: its rule, the [cron], the time [zone] and the
 * [misfire] policy, each unset to keep the annotation's (a tenant's own rule of a TENANT job comes before it);
 * [enabled] false disables a SYSTEM or a SHARDED job's instances at startup; and the [retry] and [parked] retries
 * of the subscriptions that run it, over its handler's `@Retry` and `wow.subscription.retry`, and over
 * `wow.subscription.parked`.
 */
@InternalWowApi
class ScheduleProperties {
    /** The job's cron, six fields, over its annotation's. */
    var cron: String? = null

    /** The time zone its cron is read in, over its annotation's. */
    var zone: String? = null

    /** What a firing missed while no node ran it does, over its annotation's. */
    var misfire: Misfire? = null

    /** Whether a SYSTEM or a SHARDED job's instances run; false disables them at startup. */
    var enabled: Boolean = true

    /** The in-process retry budget of the subscriptions that run it, before they park. */
    var retry: RetrySpecProperties = RetrySpecProperties()

    /** How their parked entries are retried. */
    var parked: ParkedSpecProperties = ParkedSpecProperties()

    /** These settings, the parked retries over [parkedBase], `wow.subscription.parked`'s. */
    fun options(parkedBase: ParkedRetrySpec): JobOptions =
        JobOptions(cron, zone, misfire, enabled, retry.handlerRetry(), parked.specOver(parkedBase))
}

/**
 * A retry policy: the wait grows from `initial-backoff` by `multiplier` at each failure, up to `max-backoff`.
 */
@InternalWowApi
class RetryProperties {
    /** The most attempts of a call in all, the first included. */
    var maxAttempts: Int = 1

    /** The wait after the first failure. */
    var initialBackoff: Duration = Duration.ZERO

    /** The longest wait between two attempts. */
    var maxBackoff: Duration = Duration.ZERO

    /** How many times longer each wait is than the one before; at least 1. */
    var multiplier: Double = 1.0

    /**
     * How long one attempt of a handler may run before it is cancelled and counts as failed; not set, an attempt
     * runs as long as it takes.
     */
    var timeout: Duration? = null

    fun policy(): RetryPolicy = RetryPolicy(
        maxAttempts = maxAttempts,
        initialBackoff = initialBackoff.toKotlinDuration(),
        maxBackoff = maxBackoff.toKotlinDuration(),
        multiplier = multiplier,
        timeout = timeout?.toKotlinDuration() ?: kotlin.time.Duration.INFINITE,
    )

    @InternalWowApi
    companion object {
        fun of(policy: RetryPolicy): RetryProperties = RetryProperties().apply {
            maxAttempts = policy.maxAttempts
            initialBackoff = policy.initialBackoff.toJavaDuration()
            maxBackoff = policy.maxBackoff.toJavaDuration()
            multiplier = policy.multiplier
            timeout = policy.timeout.takeUnless { it.isInfinite() }?.toJavaDuration()
        }
    }
}

/**
 * How a failing read is retried, never giving up: the wait grows from `initial-backoff` by `multiplier` at each
 * failure, up to `max-backoff`; past `error-after` attempts each failure is logged as an error.
 */
@InternalWowApi
class ReadRetryProperties {
    /** The wait after the first failure. */
    var initialBackoff: Duration = Duration.ZERO

    /** The longest wait between two attempts. */
    var maxBackoff: Duration = Duration.ZERO

    /** How many times longer each wait is than the one before; at least 1. */
    var multiplier: Double = 1.0

    /** The attempts after which each failure is logged as an error, and retried at `max-backoff`. */
    var errorAfter: Int = 1

    fun policy(): RetryPolicy = RetryPolicy(
        maxAttempts = errorAfter,
        initialBackoff = initialBackoff.toKotlinDuration(),
        maxBackoff = maxBackoff.toKotlinDuration(),
        multiplier = multiplier,
    )

    @InternalWowApi
    companion object {
        fun of(policy: RetryPolicy): ReadRetryProperties = ReadRetryProperties().apply {
            errorAfter = policy.maxAttempts
            initialBackoff = policy.initialBackoff.toJavaDuration()
            maxBackoff = policy.maxBackoff.toJavaDuration()
            multiplier = policy.multiplier
        }
    }
}
