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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The transfer saga on V10's test DSL, over the shared table of cases. V10 hands every commit of the account to each
 * handler, so an event no handler takes derives nothing because each handler skips it.
 */
class TransferSagaSpec {
    private val handlers = transferSagaHandlers()

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the saga should derive what the shared table says`(case: SagaCase) = sagaSpec(handlers) {
        val source = TransferContext.ACCOUNT_TYPE.ref(TENANT, case.source)
        whenEvent(stateEvent(source, AccountState(), case.event, version = 2)) {
            val command = case.command
            if (command == null) {
                expectNoCommand()
            } else {
                expectCommand(command, to = TransferContext.ACCOUNT_TYPE.ref(TENANT, checkNotNull(case.target)))
            }
        }
    }

    companion object {
        private const val TENANT = "tenant"

        @JvmStatic
        fun cases(): List<SagaCase> = TransferCases.SAGA
    }
}
