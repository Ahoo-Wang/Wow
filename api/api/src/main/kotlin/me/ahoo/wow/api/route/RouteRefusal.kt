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

package me.ahoo.wow.api.route

/**
 * A service route's refusal of a call: its [code], one of the route's declared errors, a [message] shown to the
 * caller, and structured [extensions], which the problem writes as its members. Its status is the
 * code's in the context's error table, `422` when the table has none.
 */
class RouteRefusal @JvmOverloads constructor(
    val code: String,
    override val message: String,
    val extensions: Map<String, Any?> = emptyMap(),
) : RuntimeException(message) {
    init {
        require(code.isNotBlank()) { "A refusal has a code." }
    }
}
