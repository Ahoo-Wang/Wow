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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.runtime.subscription.BackpressureOptions
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.SystemicFailureOptions
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.subscription.SubscriptionId
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** The runtime's own defaults, which these keys default to. */
private val DEFAULT_SUBSCRIPTION = SubscriptionOptions()
private val DEFAULT_PARKED = ParkedRetryOptions()
private val DEFAULT_SYSTEMIC = SystemicFailureOptions()
private val DEFAULT_BACKPRESSURE = BackpressureOptions()

/** How the node's subscriptions run, retry their failures and park what keeps failing. */
@InternalWowApi
class SubscriptionProperties {
    /**
     * The entries one subscription may hold in one partition between its checkpoint and the last entry it took:
     * it bounds both memory and what a crash replays.
     */
    var maxInFlight: Int = DEFAULT_SUBSCRIPTION.maxInFlight

    /** How often each subscription saves its checkpoint; a stop saves it too. */
    var checkpointInterval: Duration = DEFAULT_SUBSCRIPTION.checkpointInterval.toJavaDuration()

    /**
     * How long a stop waits for the handlers in flight before abandoning them; an abandoned entry is handled
     * again after the restart.
     */
    var stopTimeout: Duration = DEFAULT_SUBSCRIPTION.stopTimeout.toJavaDuration()

    /**
     * How a failing handler is retried in process before its aggregate is parked; a subscription's
     * `wow.subscriptions.[<context>.<name>].retry.*` and its handler's `@Retry` come before it.
     */
    var retry: RetryProperties = RetryProperties.of(DEFAULT_SUBSCRIPTION.retry)

    /**
     * How a failing read of the log, or write of a store, is retried: until the node stops, the backoff growing
     * for `error-after` (5) attempts, after which each failure is logged as an error and retried at
     * `max-backoff`, so a long outage does not stop the subscription.
     */
    var readRetry: ReadRetryProperties = ReadRetryProperties.of(DEFAULT_SUBSCRIPTION.readRetry)

    /** How the parked entries are retried automatically, over minutes and hours. */
    var parked: ParkedProperties = ParkedProperties()

    /**
     * When a partition pauses on a systemic failure, such as a database that is down, instead of parking every
     * aggregate that fails.
     */
    var systemic: SystemicProperties = SystemicProperties()

    /** How a handler whose downstream pushes back is retried, and its partition throttled. */
    var backpressure: BackpressureProperties = BackpressureProperties()

    /**
     * The partitions the node reads behind their head at once, all its subscriptions together; 0 for no bound. A
     * partition whose subscriptions start behind the head (a history migrate-data imported, a long stop) waits for
     * one, and the others stand at their checkpoints, so the node keeps its store for its commands while it
     * catches up. More do not catch up sooner on one store, and raise the latency of the commands meanwhile.
     */
    var maxCatchUps: Int = DEFAULT_SUBSCRIPTION.maxCatchUps

    /** These options, with the starts, the parked retry specs and the retry budgets [subscriptions] configure. */
    fun options(subscriptions: Map<String, SubscriptionSettingsProperties> = emptyMap()): SubscriptionOptions {
        val spec = parked.spec()
        val configured = subscriptions.mapKeys { (key, _) -> subscriptionOf(key) }
        return SubscriptionOptions(
            maxInFlight = maxInFlight,
            retry = retry.policy(),
            checkpointInterval = checkpointInterval.toKotlinDuration(),
            stopTimeout = stopTimeout.toKotlinDuration(),
            readRetry = readRetry.policy(),
            starts = configured.mapNotNull { (id, settings) ->
                settings.start?.takeIf { it.isNotBlank() }?.let { id to SubscriptionStart.parse(it) }
            }.toMap(),
            parked = ParkedRetryOptions(
                spec = spec,
                specs = configured.mapNotNull { (id, settings) -> settings.parked.specOver(spec)?.let { id to it } }
                    .toMap(),
                scanInterval = parked.scanInterval.toKotlinDuration(),
            ),
            retries = configured.mapNotNull { (id, settings) ->
                settings.retry.handlerRetry()?.let { id to it }
            }.toMap(),
            systemic = systemic.options(),
            backpressure = backpressure.options(),
            maxCatchUps = maxCatchUps,
        )
    }

    /**
     * The subscription `wow.subscriptions.[<key>]` configures, refused unless it reads `<context>.<name>`: a
     * [WowConfigurationException] of the key.
     */
    private fun subscriptionOf(key: String): SubscriptionId = configNotNull(
        SubscriptionId.parse(key),
        "wow.subscriptions.[$key]",
        "Name it by its context and name, wow.subscriptions.[<context>.<name>], such as " +
            "wow.subscriptions.[shop.CartSaga.onOrderCreated].",
        key,
    ) { "wow.subscriptions.[$key] names no subscription: a subscription is configured by its context and name." }
}

/**
 * How a subscription answers backpressure: a downstream that is up but asks to be called later
 * (`wow.command.busy`, `wow.wait.timeout`, `wow.query.busy`, a 429 with `Retry-After`). The entry is retried, then
 * its aggregate parks; it never pauses the partition. Meanwhile each backpressure halves the handlings the
 * partition runs at once, and each success raises them again.
 */
@InternalWowApi
class BackpressureProperties {
    /** The most attempts of an entry that meets backpressure before its aggregate parks. */
    var maxAttempts: Int = DEFAULT_BACKPRESSURE.maxAttempts

    /** The wait before the first retry; it doubles at each next one. */
    var initialBackoff: Duration = DEFAULT_BACKPRESSURE.initialBackoff.toJavaDuration()

    /** The longest wait between two retries; a downstream's longer `Retry-After` still counts. */
    var maxBackoff: Duration = DEFAULT_BACKPRESSURE.maxBackoff.toJavaDuration()

    /**
     * The share of each wait, 0 to 1, by which it is shortened at random, so that retries do not come in step.
     */
    var jitter: Double = DEFAULT_BACKPRESSURE.jitter

    /** The fewest handlings a throttled partition runs at once. */
    var minConcurrency: Int = DEFAULT_BACKPRESSURE.minConcurrency

    fun options(): BackpressureOptions = BackpressureOptions(
        maxAttempts = maxAttempts,
        initialBackoff = initialBackoff.toKotlinDuration(),
        maxBackoff = maxBackoff.toKotlinDuration(),
        jitter = jitter,
        minConcurrency = minConcurrency,
    )
}

/**
 * When a subscription pauses its partition on a systemic failure: at once on a failure classified systemic, or
 * when enough different aggregates failed within a window with no handling succeeding since. While paused, one
 * attempt at a time probes until a handling succeeds. A handler's failures are classified by its
 * `@Retry(recoverable, unrecoverable)`, then the `FailureClassifier` beans, then the modules' on the classpath,
 * then the runtime's own.
 */
@InternalWowApi
class SystemicProperties {
    /** How many different aggregates failing within `window` pause the partition; 0 turns that off. */
    var failingAggregates: Int = DEFAULT_SYSTEMIC.failingAggregates

    /** The window in which `failing-aggregates` failures pause the partition. */
    var window: Duration = DEFAULT_SYSTEMIC.window.toJavaDuration()

    /** The wait before the first probe of a paused partition; it doubles at each next one. */
    var initialBackoff: Duration = DEFAULT_SYSTEMIC.initialBackoff.toJavaDuration()

    /** The longest wait between two probes of a paused partition. */
    var maxBackoff: Duration = DEFAULT_SYSTEMIC.maxBackoff.toJavaDuration()

    fun options(): SystemicFailureOptions = SystemicFailureOptions(
        failingAggregates = failingAggregates,
        window = window.toKotlinDuration(),
        initialBackoff = initialBackoff.toKotlinDuration(),
        maxBackoff = maxBackoff.toKotlinDuration(),
    )
}

/**
 * The automatic retries of parked entries over minutes and hours; per subscription,
 * `wow.subscriptions.[<context>.<name>].parked.*` overrides them.
 */
@InternalWowApi
class ParkedProperties {
    /** The most automatic retries of a parked entry; 0 turns them off. */
    var maxRetries: Int = DEFAULT_PARKED.spec.maxRetries

    /**
     * The wait before the first retry after the failure; each next one waits twice as long after the previous one
     * started.
     */
    var initialBackoff: Duration = DEFAULT_PARKED.spec.initialBackoff.toJavaDuration()

    /**
     * How long a retry's claim on the entry holds before another retry may start, such as on the partition's next
     * owner: it cancels nothing.
     */
    var claimTtl: Duration = DEFAULT_PARKED.spec.claimTtl.toJavaDuration()

    /** How often each node scans its subscriptions' parked entries for retries that are due. */
    var scanInterval: Duration = DEFAULT_PARKED.scanInterval.toJavaDuration()

    fun spec(): ParkedRetrySpec =
        ParkedRetrySpec(maxRetries, initialBackoff.toKotlinDuration(), claimTtl.toKotlinDuration())
}

/**
 * One subscription's retry spec of its parked entries, each setting not given being `wow.subscription.parked`'s.
 */
@InternalWowApi
class ParkedSpecProperties {
    /** How many automatic retries a parked entry of the subscription gets, over `wow.subscription.parked`'s. */
    var maxRetries: Int? = null

    /** The wait before the first automatic retry, over `wow.subscription.parked`'s. */
    var initialBackoff: Duration? = null

    /** How long a retry's claim on the entry holds, over `wow.subscription.parked`'s. */
    var claimTtl: Duration? = null

    /** The spec these settings give over [base], null when none is given. */
    fun specOver(base: ParkedRetrySpec): ParkedRetrySpec? {
        if (maxRetries == null && initialBackoff == null && claimTtl == null) {
            return null
        }
        return ParkedRetrySpec(
            maxRetries ?: base.maxRetries,
            initialBackoff?.toKotlinDuration() ?: base.initialBackoff,
            claimTtl?.toKotlinDuration() ?: base.claimTtl,
        )
    }
}

/**
 * One subscription's settings. [start] is where it starts in a partition it has no checkpoint in yet: `beginning`,
 * `end`, or an ISO-8601 instant (`2026-10-01T00:00:00Z`), the first entry committed at or after it; unset, its
 * kind's default (a projection from the beginning, a reactor or a saga from the end). A subscription that has a
 * checkpoint goes on from it. [parked] is how its parked entries are retried, over `wow.subscription.parked`.
 * [retry] is its in-process retry budget before it parks, over its handler's `@Retry` and `wow.subscription.retry`.
 * A name the node has no subscription of is refused at startup, and so is the subscription of an `@OnSchedule` job,
 * whose settings are its job's, `wow.schedules.<job>.*`.
 */
@InternalWowApi
class SubscriptionSettingsProperties {
    /** Where the subscription starts in a partition it has no checkpoint in: `beginning`, `end` or an instant. */
    var start: String? = null
    var parked: ParkedSpecProperties = ParkedSpecProperties()
    var retry: RetrySpecProperties = RetrySpecProperties()
}

/**
 * One subscription's in-process retry budget: each setting given replaces its handler's `@Retry`
 * one, each one not given is the handler's, else `wow.subscription.retry`'s. [timeout] bounds one attempt: one
 * that runs longer is cancelled and counts as failed.
 */
@InternalWowApi
class RetrySpecProperties {
    /** The most attempts of the handler in all, before its aggregate parks. */
    var maxAttempts: Int? = null

    /** The wait after the first failure. */
    var initialBackoff: Duration? = null

    /** The longest wait between two attempts. */
    var maxBackoff: Duration? = null

    /** How many times longer each wait is than the one before; at least 1. */
    var multiplier: Double? = null

    /** How long one attempt may run before it is cancelled and counts as failed. */
    var timeout: Duration? = null

    /** The budget these settings give, null when none is given. */
    fun handlerRetry(): HandlerRetry? = HandlerRetry(
        maxAttempts = maxAttempts,
        initialBackoff = initialBackoff?.toKotlinDuration(),
        maxBackoff = maxBackoff?.toKotlinDuration(),
        multiplier = multiplier,
        timeout = timeout?.toKotlinDuration(),
    ).takeUnless { it.isEmpty }
}
