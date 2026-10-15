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

package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandRequest
import org.springframework.stereotype.Service

@Service
class Teller(private val commands: CommandGateway) {
    suspend fun deposit(accountId: String, amount: Long): CommandResult {
        val request = CommandRequest.builder(
            context = "bank",
            aggregate = "account",
            command = "Deposit",
            body = CommandBody.of("""{"amount":$amount}"""),
        ).aggregateId(accountId).build()
        return commands.execute(request)
    }
}
