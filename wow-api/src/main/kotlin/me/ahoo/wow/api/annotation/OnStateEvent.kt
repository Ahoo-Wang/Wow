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

import me.ahoo.wow.api.messaging.function.FunctionKind
import java.lang.annotation.Inherited

const val DEFAULT_ON_STATE_EVENT_NAME = "onStateEvent"

/**
 * Marks a function as a handler for state events, indicating that the function should be invoked when a state event is emitted.
 * This annotation is used in conjunction with the [OnMessage] annotation to specify the type of message and default function name.
 *
 * The `value` parameter allows specifying one or more aggregate names to which this state event handler applies.
 *
 * Examples:
 *
 * ``` kotlin
 *    @OnStateEvent
 *    fun onStateEvent(changed: Changed, state: State) {
 *         //...
 *     }
 * ```
 *
 * Remote Context:
 *
 * ``` kotlin
 *    @OnStateEvent
 *    fun onStateEvent(changed: Changed, stateRecord: StateRecord) {
 *      val state = stateRecord.toObject<StateData>()
 *         //...
 *     }
 * ```
 *
 * @author ahoo wang
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@OnMessage(FunctionKind.STATE_EVENT, DEFAULT_ON_STATE_EVENT_NAME)
@MustBeDocumented
annotation class OnStateEvent(
    /**
     * aggregate Names
     */
    vararg val value: String
)
