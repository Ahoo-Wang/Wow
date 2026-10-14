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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.result.FunctionKind

/**
 * The subscriptions of [context] as [runtime] knows them, and the `@OnCommitted` handlers of its sagas, [handlers]
 * (decision D192): each is a saga's hop, which the receipt of a command it answers signals with the commands it
 * derived, though no subscription of the log runs it. It handles no stream, so it covers no aggregate type.
 */
internal class ReceiptHops(
    private val runtime: SubscriptionDirectory,
    private val context: String,
    private val handlers: Set<String>,
) : SubscriptionDirectory by runtime {
    override fun kindOf(context: String, subscription: String): FunctionKind? =
        runtime.kindOf(context, subscription)
            ?: FunctionKind.SAGA.takeIf { context == this.context && subscription in handlers }
}
