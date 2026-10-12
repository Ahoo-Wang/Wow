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

package me.ahoo.wow.api.decide

/**
 * Where a command comes from (documentation/designs/v10/08-data-protocols.md §4): [correlationId] is the id of the
 * root command of the chain, and [causationId] the direct upstream, such as the event stream (`ref@version`) a saga
 * reacted to. A command a user sends directly carries none: it is its own root.
 */
data class Causation(val correlationId: String, val causationId: String) {
    init {
        require(correlationId.isNotBlank()) { "A causation needs a correlation id." }
        require(causationId.isNotBlank()) { "A causation needs a causation id." }
    }
}
