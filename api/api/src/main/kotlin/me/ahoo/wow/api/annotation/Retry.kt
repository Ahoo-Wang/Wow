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

package me.ahoo.wow.api.annotation

import kotlin.reflect.KClass

/**
 * The in-process retry budget of a subscription handler (in place of 9.x `@Retry`): how often a
 * failing call is attempted, how long it waits between attempts, and how long one attempt may run, before the
 * aggregate is parked in the failure ledger. A [Projection], [Reactor] or [Saga] class carries it for all its
 * [OnEvent] handlers, and a saga's for its [OnSchedule] handlers too; a handler's own overrides it, setting by setting.
 * A saga's [OnRejected] and [OnCommitted] handlers take none: a receipt of the saga's own context is handled under the
 * budget of the [OnEvent] handler that derived the command, one from another context under the host's retry of the
 * context's receipts subscription, `wow.receipts.<context>`.
 *
 * ```kotlin
 * @Reactor
 * @Retry(maxAttempts = 3, timeout = "2s")
 * class ShippingNotifier(private val carrier: CarrierClient) {
 *     @OnEvent
 *     @Retry(initialBackoff = "1s")
 *     suspend fun onShipped(event: StateEvent<OrderState>) = carrier.notify(event.state.id)
 * }
 * ```
 *
 * A setting left at its default is not given: it comes from the processor's annotation, else from
 * `wow.subscription.retry.*`. The host's `wow.subscriptions.[<context>.<processor.function>].retry.*` overrides the
 * annotation.
 * Durations are written as Kotlin durations (`500ms`, `2s`, `1m 30s`) or ISO-8601 (`PT2S`).
 *
 * - [maxAttempts]: the calls in all, at least 1; 0 is not given.
 * - [initialBackoff]: the wait after the first failure; each next wait is [multiplier] times longer.
 * - [maxBackoff]: the longest wait between attempts.
 * - [multiplier]: how many times longer each wait is than the one before, at least 1; 0 is not given.
 * - [timeout]: how long one attempt may run; an attempt that runs longer is cancelled and counts as failed.
 *
 * - [recoverable], [unrecoverable], [systemic]: the exception classes this handler classifies itself, each with its
 *   subclasses, one class each. A failure of a [recoverable] class is the aggregate's own, which a retry may get past:
 *   it is retried within this budget, then parks the aggregate. One of an [unrecoverable] class is the aggregate's own
 *   too, but no retry changes it: it parks the aggregate at once. One of a [systemic] class is not the aggregate's
 *   but its surroundings' (a downstream the handler calls that is down): the subscription pauses the whole partition
 *   and retries until it is back, spending no aggregate's budget and parking none. When several lists hold a class the
 *   failure is an instance of, the nearest one in its class hierarchy decides; on a tie, [recoverable], then
 *   [systemic]. A class no list holds is classified by the classifiers (the SPI's `FailureClassifier`: the
 *   application's, then the modules' on the classpath), else retried within the budget. A handler's own lists replace
 *   its processor's. A handler may also throw `me.ahoo.wow.api.subscribe.UnrecoverableHandlingException` to park the
 *   aggregate at once. The host's configuration classifies nothing: these lists are the handler's.
 *
 * ```kotlin
 * @OnEvent
 * @Retry(
 *     unrecoverable = [IllegalArgumentException::class],
 *     recoverable = [CarrierBusyException::class],
 *     systemic = [CarrierUnreachableException::class],
 * )
 * suspend fun onShipped(event: StateEvent<OrderState>) = carrier.notify(event.state.id)
 * ```
 *
 * A rejection (a business outcome) is never retried. Once the aggregate is parked, the long-horizon
 * retries of the failure ledger (`wow.subscription.parked.*`) take over.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class Retry(
    val maxAttempts: Int = 0,
    val initialBackoff: String = "",
    val maxBackoff: String = "",
    val multiplier: Double = 0.0,
    val timeout: String = "",
    val recoverable: Array<KClass<out Throwable>> = [],
    val unrecoverable: Array<KClass<out Throwable>> = [],
    val systemic: Array<KClass<out Throwable>> = [],
)
