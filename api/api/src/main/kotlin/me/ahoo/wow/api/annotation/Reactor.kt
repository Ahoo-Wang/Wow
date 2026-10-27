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
 * A reactor: a processor whose [OnEvent] functions act on committed events, such as sending a
 * notification or calling another system. It replaces 9.x's `@EventProcessor`.
 *
 * Each handler is compiled into its own subscription, named `processor.function`. A handler takes the
 * committed stream with the state after it, and optionally the [me.ahoo.wow.api.subscribe.ReactContext], whose
 * `executionId` is the idempotency key for the outside call:
 *
 * ```kotlin
 * @Reactor
 * class OrderNotifier(private val mail: Mail) {
 *     @OnEvent
 *     fun onEvent(event: StateEvent<OrderState>, context: ReactContext) {
 *         mail.send(event.state.customer, "Order shipped", key = context.executionId.toString())
 *     }
 * }
 * ```
 *
 * A handler returns nothing, and may be `suspend` or return a Reactor `Mono`. A failure parks the aggregate in the
 * failure ledger. The processor's dependencies are its constructor's parameters.
 *
 * A processor is of exactly one kind: [Projection], [Reactor] or [Saga].
 */
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class Reactor
