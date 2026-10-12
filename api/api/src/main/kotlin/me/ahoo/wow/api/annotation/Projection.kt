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
 * A projection (V10, decision D67): a processor whose [OnEvent] functions write a read model from committed events.
 *
 * Each handler is compiled into its own subscription, named `processor.function` (design 04 §1), where the processor
 * is the class's [Name] or its simple name. A handler takes the committed stream with the state after it, and
 * optionally the [me.ahoo.wow.api.subscribe.ProjectContext]:
 *
 * ```kotlin
 * @Projection
 * class CartSummaryProjection(private val repository: CartSummaries) {
 *     @OnEvent
 *     fun onEvent(event: StateEvent<CartState>, context: ProjectContext) {
 *         repository.upsert(event.state, ifNewer = event.version)
 *     }
 * }
 * ```
 *
 * A handler returns nothing, and may be `suspend` or return a Reactor `Mono`. It may do I/O; its writes are
 * guarded by version, since a commit may be handled more than once. A failure parks the aggregate in the failure
 * ledger. The processor's dependencies are its constructor's parameters.
 *
 * A processor is of exactly one kind: [Projection], [Reactor] or [Saga].
 */
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class Projection
