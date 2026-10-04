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

import java.lang.annotation.Inherited

/**
 * Declares whether an aggregate is spaced. Since 9.3.0.
 *
 * A spaced aggregate takes a space from its commands: the command factory writes the command's space (for HTTP, the
 * `Wow-Space-Id` header; it adds no path segment), the aggregate checks it against the space it was created in, and
 * its queries are scoped by the space a request states. A command to any other aggregate carries the default space
 * whatever its source (HTTP, a saga reacting to a spaced aggregate's event, in-process), and that aggregate neither
 * checks nor records a command's space.
 *
 * The annotation's presence is the declaration: `@Spaced` declares a spaced aggregate, `@Spaced(false)` declares one
 * that is not. Without it anywhere in the aggregate's hierarchy, the deprecated `@AggregateRoute(spaced = …)` still
 * applies, and an aggregate that declares neither is not spaced. With it, the nearest class that declares the policy
 * in either form decides, so `@Spaced(false)` on an aggregate overrides `@Spaced` or `@AggregateRoute(spaced = true)`
 * on its supertype. Both forms on the same class with different values fail at startup and, under the Wow KSP
 * processor, at compile time.
 *
 * ```kotlin
 * @AggregateRoot
 * @Spaced
 * class Order(private val state: OrderState)
 * ```
 *
 * @param value whether the aggregate is spaced. Defaults to true.
 * @see AggregateOwner
 */
@Target(AnnotationTarget.CLASS)
@Inherited
@MustBeDocumented
annotation class Spaced(
    val value: Boolean = true
)
