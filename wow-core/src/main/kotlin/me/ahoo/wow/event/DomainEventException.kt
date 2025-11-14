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

package me.ahoo.wow.event

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.WowException

/**
 * Exception wrapper for domain events containing error information.
 *
 * This exception is thrown when a domain event contains error details that need to be
 * propagated as an exception. It wraps the original domain event and extracts the
 * error code and message from the event body.
 *
 * @property domainEvent The domain event containing error information
 * @constructor Creates a new DomainEventException from a domain event with error info
 *
 * @param domainEvent The domain event that contains error details
 * @throws IllegalArgumentException if the domain event body is not ErrorInfo
 *
 * @see WowException
 * @see ErrorInfo
 * @see DomainEvent
 */
class DomainEventException(
    val domainEvent: DomainEvent<out ErrorInfo>
) : WowException(domainEvent.body.errorCode, domainEvent.body.errorMsg) {
    companion object {
        /**
         * Extension function to convert a domain event with error info to a DomainEventException.
         *
         * @receiver The domain event containing error information
         * @return A new DomainEventException wrapping this domain event
         *
         * @see DomainEventException
         */
        fun DomainEvent<out ErrorInfo>.toException(): DomainEventException = DomainEventException(this)
    }
}
