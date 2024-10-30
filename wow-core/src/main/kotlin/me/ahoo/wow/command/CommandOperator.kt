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

import me.ahoo.wow.api.messaging.Header

object CommandOperator {
    private const val OPERATOR_HEADER = "command_operator"
    val Header.operator: String?
        get() {
            return this[OPERATOR_HEADER]
        }
    val Header.requiredOperator: String
        get() {
            return checkNotNull(operator) { "operator is required!" }
        }

    fun Header.withOperator(operator: String): Header {
        return this.with(OPERATOR_HEADER, operator)
    }
}
