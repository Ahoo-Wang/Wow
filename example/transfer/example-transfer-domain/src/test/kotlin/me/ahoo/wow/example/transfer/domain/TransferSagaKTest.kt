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

package me.ahoo.wow.example.transfer.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.transfer.api.AmountEntered
import me.ahoo.wow.example.transfer.api.Confirm
import me.ahoo.wow.example.transfer.api.Entry
import me.ahoo.wow.example.transfer.api.EntryFailed
import me.ahoo.wow.example.transfer.api.Prepared
import me.ahoo.wow.example.transfer.api.UnlockAmount
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import org.junit.jupiter.api.Test

internal class TransferSagaKTest {

    @Test
    fun onPrepared() {
        val event = Prepared("to", 1)
        sagaVerifier<TransferSaga>()
            .whenEvent(event)
            .expectCommandBody<Entry> {
                id.assert().isEqualTo(event.to)
                amount.assert().isEqualTo(event.amount)
            }
            .verify()
    }

    @Test
    fun onAmountEntered() {
        val event = AmountEntered("sourceId", 1)
        sagaVerifier<TransferSaga>()
            .whenEvent(event)
            .expectCommandBody<Confirm> {
                id.assert().isEqualTo(event.sourceId)
                amount.assert().isEqualTo(event.amount)
            }
            .verify()
    }

    @Test
    fun onEntryFailed() {
        val event = EntryFailed("sourceId", 1)
        sagaVerifier<TransferSaga>()
            .whenEvent(event)
            .expectCommandBody<UnlockAmount> {
               id.assert().isEqualTo(event.sourceId)
               amount.assert().isEqualTo(event.amount)
            }
            .verify()
    }
}
