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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The transfer saga on 9.x's test DSL, over the shared table of cases. 9.x subscribes a handler to its event type, so
 * an event no handler takes derives nothing because nothing handles it.
 */
class TransferSagaSpec {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the saga should derive what the shared table says`(case: SagaCase) {
        val event = case.event.toDomainEvent(
            aggregateId = case.source,
            tenantId = TenantId.DEFAULT_TENANT_ID,
            commandId = "c"
        )
        if (case.command == null) {
            // The verifier finds no handler of the event's type.
            assertThrows<NoSuchElementException> { sagaVerifier<TransferSaga>().whenEvent(event) }
            return
        }
        sagaVerifier<TransferSaga>().whenEvent(event).expectNoError()
            .expect {
                val derived = commandStream!!.single()
                derived.body.assert().isEqualTo(case.command)
                derived.aggregateId.id.assert().isEqualTo(case.target)
            }
            .verify()
    }

    companion object {
        @JvmStatic
        fun cases(): List<SagaCase> = TransferCases.SAGA
    }
}
