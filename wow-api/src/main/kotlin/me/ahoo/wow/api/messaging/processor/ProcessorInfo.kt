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

package me.ahoo.wow.api.messaging.processor

import me.ahoo.wow.api.naming.NamedBoundedContext

/**
 * Represents information about a message processor.
 *
 * Processors are components that handle messages within a bounded context.
 * This interface provides the essential metadata needed to identify and
 * locate processors in the messaging system, combining bounded context
 * information with a specific processor name.
 */
interface ProcessorInfo : NamedBoundedContext {
    /**
     * The name of the processor.
     *
     * This name uniquely identifies the processor within its bounded context
     * and is used for routing messages, configuration, and monitoring purposes.
     */
    val processorName: String
}
