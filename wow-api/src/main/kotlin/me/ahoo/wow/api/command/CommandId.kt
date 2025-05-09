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

package me.ahoo.wow.api.command

/**
 * Represents a unique identifier for a command. This interface is part of the command handling mechanism, ensuring that each command can be uniquely identified.
 *
 * Implementations of this interface are expected to provide a `commandId` which should be unique and used to identify the specific command instance. This is particularly useful in scenarios
 *  where idempotency of commands needs to be guaranteed or when tracking and correlating commands across system boundaries.
 */
interface CommandId {
    /**
     * Represents a unique identifier for a command. This identifier is crucial for ensuring that each command can be uniquely identified, which is particularly useful in scenarios where idempotency of commands needs to be
     *  guaranteed or when tracking and correlating commands across system boundaries.
     */
    val commandId: String
}
