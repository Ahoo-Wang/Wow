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

package me.ahoo.wow.spi.subscription

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * A subscription by the bounded [context] it belongs to and its [name] (`processor.function`, or a built-in's
 * `wow.…`): names are unique within a context only, so two contexts of one service may each have a subscription of
 * one name, such as their `wow.snapshot`. It keys the failure ledger's entries
 * ([me.ahoo.wow.spi.ledger.FailureLedger]), so the contexts of a service that share a store keep their entries apart;
 * and it identifies a subscription to the operator (the parked entries' routes) and in the host's configuration, as its
 * [key] `<context>.<name>` (`wow.subscriptions.[shop.CartSaga.onOrderCreated]`): a context's name has no dot, so the
 * key splits at its first.
 *
 * Construct one with [of]; the constructor is internal to Wow.
 */
class SubscriptionId @InternalWowApi constructor(val context: String, val name: String) : Comparable<SubscriptionId> {
    init {
        require(context.isNotBlank() && '.' !in context) { "A subscription's context is a context's name: '$context'." }
        require(name.isNotBlank()) { "A subscription has a name." }
    }

    /** `<context>.<name>`, as the host's configuration keys it. */
    val key: String get() = "$context.$name"

    override fun compareTo(other: SubscriptionId): Int = compareValuesBy(this, other, { it.context }, { it.name })

    override fun equals(other: Any?): Boolean =
        this === other || other is SubscriptionId && context == other.context && name == other.name

    override fun hashCode(): Int = 31 * context.hashCode() + name.hashCode()

    override fun toString(): String = key

    companion object {
        /** The subscription [name] of [context]. */
        @JvmStatic
        fun of(context: String, name: String): SubscriptionId = SubscriptionId(context, name)

        /** The subscription [key] names (`<context>.<name>`); null when it names no context and no subscription. */
        @InternalWowApi
        @JvmStatic
        fun parse(key: String): SubscriptionId? {
            val dot = key.indexOf('.')
            if (dot <= 0 || dot == key.length - 1) {
                return null
            }
            return SubscriptionId(key.substring(0, dot), key.substring(dot + 1))
        }
    }
}
