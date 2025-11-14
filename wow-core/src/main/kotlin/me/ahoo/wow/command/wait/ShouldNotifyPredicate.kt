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
 * Used by wait strategies to decide if they should be notified about
 * command processing progress at specific stages.
 */
fun interface ProcessingStageShouldNotifyPredicate {
    /**
     * Evaluates whether notification is required for the given processing stage.
     * This allows wait strategies to filter notifications based on their waiting criteria.
     *
     * @param processingStage The command processing stage being evaluated.
     * @return true if notification should be sent for this stage, false otherwise.
     */
    fun shouldNotify(processingStage: CommandStage): Boolean
}

/**
 * Functional interface for defining predicate logic to determine
 * whether notification should be sent for specific wait signals.
 * Provides fine-grained control over when wait strategies receive notifications
 * based on the content and context of the signal.
 */
fun interface WaitSignalShouldNotifyPredicate {
    /**
     * Evaluates whether the wait signal should trigger a notification.
     * Allows for complex filtering logic based on signal properties like
     * stage, function info, error status, etc.
     *
     * @param signal The wait signal being evaluated for notification.
     * @return true if notification should be sent for this signal, false otherwise.
     */
    fun shouldNotify(signal: WaitSignal): Boolean
}
