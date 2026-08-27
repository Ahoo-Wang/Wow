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

package me.ahoo.wow.api.messaging.function

/**
 * Interface for entities that have a function name.
 *
 * This interface provides access to a function name, which can be used for
 * identification, logging, and routing purposes. The function name is typically
 * unique within a specific context or processor.
 */
interface FunctionNameCapable {
    /**
     * The name of the function.
     *
     * This name uniquely identifies the function within its scope and is used
     * for function resolution, invocation, and metadata purposes.
     */
    val functionName: String
}
