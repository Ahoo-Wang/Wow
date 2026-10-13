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

/**
 * A saga (V10, decision D67): a processor whose [OnEvent] functions derive commands from committed events. It
 * replaces 9.x's `@StatelessSaga`, and compensation that 9.x wrote with `@OnError` is written as a saga.
 *
 * Each handler is compiled into its own subscription, named `processor.function` (design 04 §1). A handler takes the
 * committed stream with the state after it, and optionally the [me.ahoo.wow.api.subscribe.SagaContext], and returns
 * the commands it derives: one [me.ahoo.wow.api.decide.Command], or a list of them (empty or null for none).
 *
 * ```kotlin
 * @Saga
 * class PaymentSaga {
 *     @OnEvent
 *     fun onEvent(event: StateEvent<OrderState>, context: SagaContext): List<Command<*>> =
 *         event.events.filterIsInstance<OrderPlaced>().map {
 *             context.command(paymentRef(it), RequestPayment(it.amount))
 *         }
 * }
 * ```
 *
 * A handler may be `suspend` or return a Reactor `Mono` or `Flux`. Derived commands have deterministic request ids,
 * so handling a commit again derives the same requests. The saga is done when its commands are committed (or, across
 * contexts, durably accepted, decision D33); a derived command that is rejected goes to the saga's [OnRejected]
 * handler of its type, and parks the aggregate in the failure ledger only when the saga has none (decision D153).
 * The processor's dependencies are its constructor's parameters.
 *
 * A processor is of exactly one kind: [Projection], [Reactor] or [Saga].
 */
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class Saga
