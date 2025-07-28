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

package me.ahoo.wow.command.wait

/**
 * Functional interface for defining predicate logic to determine
 * whether notification should be sent during processing stages.
 */
fun interface ProcessingStageShouldNotifyPredicate {
    /**
     * Early assertions, used to determine whether notification is required.
     *
     * @param processingStage the command processing stage enum value
     * @return true if notification is required, false otherwise
     */
    fun shouldNotify(processingStage: CommandStage): Boolean
}

/**
 * Functional interface for defining predicate logic to determine
 * whether notification should be sent for wait signals.
 */
fun interface WaitSignalShouldNotifyPredicate {
    /**
     * Determine whether the wait signal should trigger a notification.
     *
     * @param signal the wait signal object containing waiting conditions and context
     * @return true if notification is required, false otherwise
     */
    fun shouldNotify(signal: WaitSignal): Boolean
}
