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

package me.ahoo.wow.messaging

import reactor.core.publisher.Flux
import reactor.util.context.Context
import reactor.util.context.ContextView

/**
 * Constant key used to store the receiver group in Reactor context.
 */
const val RECEIVER_GROUP = "(ReceiverGroup)"

/**
 * Retrieves the receiver group from the Reactor context.
 *
 * @receiver The context view to read from
 * @return The receiver group string
 * @throws NoSuchElementException if the receiver group is not present in the context
 */
fun ContextView.getReceiverGroup(): String = get(RECEIVER_GROUP)

/**
 * Sets the receiver group in the Reactor context.
 *
 * @receiver The context to modify
 * @param receiverGroup The receiver group string to set
 * @return A new context with the receiver group set
 */
fun Context.setReceiverGroup(receiverGroup: String): Context = this.put(RECEIVER_GROUP, receiverGroup)

/**
 * Writes the receiver group to the Reactor context for this Flux.
 *
 * This allows downstream operators to access the receiver group via the context.
 *
 * @param receiverGroup The receiver group to write to the context
 * @return A new Flux with the receiver group in its context
 */
fun <T> Flux<T>.writeReceiverGroup(receiverGroup: String): Flux<T> =
    contextWrite {
        it.setReceiverGroup(receiverGroup)
    }
