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

package me.ahoo.wow.command

/**
 * Interface for objects that can provide command execution results.
 * Implementations of this interface expose their results as a map of key-value pairs,
 * allowing for flexible result representation and access.
 */
interface CommandResultCapable {
    /**
     * The result of the command execution as a map.
     * Keys represent result field names, values are the corresponding data.
     *
     * @return A map containing the command execution results.
     */
    val result: Map<String, Any>
}
