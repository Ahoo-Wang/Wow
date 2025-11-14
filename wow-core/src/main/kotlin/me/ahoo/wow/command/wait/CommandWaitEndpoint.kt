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
 * Interface for defining command wait endpoints.
 * Command wait endpoints specify where command processing results should be sent
 * when using wait strategies in distributed scenarios.
 */
interface CommandWaitEndpoint {
    /**
     * The endpoint address for command wait notifications.
     * This could be a URL, queue name, or other addressing mechanism
     * depending on the underlying messaging infrastructure.
     */
    val endpoint: String
}

/**
 * Simple implementation of CommandWaitEndpoint.
 * Provides a basic data class wrapper for endpoint strings.
 *
 * @param endpoint The endpoint address string.
 */
data class SimpleCommandWaitEndpoint(
    override val endpoint: String
) : CommandWaitEndpoint
