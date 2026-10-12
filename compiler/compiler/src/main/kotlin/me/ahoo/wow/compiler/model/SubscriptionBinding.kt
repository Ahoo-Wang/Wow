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

/**
 * A subscription processor as the model processor read it: a class of one kind (`@Projection`, `@Reactor` or
 * `@Saga`) whose `@OnEvent` functions each become a subscription named `processor.function` (design 04 §1).
 */
internal data class SubscriptionBinding(
    /** The processor's qualified class name. */
    val processor: String,
    /** The processor's name: its `@Name`, or its class's simple name. */
    val name: String,
    /** `PROJECT`, `REACT` or `SAGA`; null when the class declares no kind or more than one. */
    val kind: String?,
    val handlers: List<HandlerBinding>,
    val problems: List<String>,
)

internal data class HandlerBinding(
    val function: String,
    /** The subscription the handler is compiled into: `processor.function`. */
    val subscription: String,
    /** The state type of the `StateEvent` it takes; null for a star projection. */
    val state: String?,
    /** Whether it takes its kind's context after the state event. */
    val context: Boolean,
    val suspending: Boolean,
    /** Whether it returns a Reactor publisher. */
    val reactor: Boolean,
)
