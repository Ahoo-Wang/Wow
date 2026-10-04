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
 * Declares an aggregate's [ownership policy][OwnerPolicy]. Since 9.3.0.
 *
 * The policy decides whether the aggregate's routes take an owner (an `owner/{ownerId}` path prefix or the
 * `Wow-Owner-Id` header), whether loading its state checks that owner, and whether the owner ID is the aggregate ID.
 *
 * The annotation's presence is the declaration. Without it, the deprecated `@AggregateRoute(owner = …)` still
 * applies, and an aggregate that declares neither is [OwnerPolicy.NEVER]. Declaring both with different policies
 * fails at startup and, under the Wow KSP processor, at compile time.
 *
 * ```kotlin
 * @AggregateRoot
 * @AggregateOwner(OwnerPolicy.AGGREGATE_ID)
 * class Cart(private val state: CartState)
 * ```
 *
 * @param value the ownership policy.
 * @see Spaced
 * @see OwnerId
 */
@Target(AnnotationTarget.CLASS)
@Inherited
@MustBeDocumented
annotation class AggregateOwner(
    val value: OwnerPolicy
)
