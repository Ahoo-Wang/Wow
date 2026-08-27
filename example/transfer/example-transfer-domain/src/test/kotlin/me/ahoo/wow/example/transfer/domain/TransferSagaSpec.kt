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
import me.ahoo.wow.test.SagaSpec

class TransferSagaSpec : SagaSpec<TransferSaga>({
    on {
        val prepared = Prepared("to", 1)
        whenEvent(prepared) {
            expectNoError()
            expectCommandType(Entry::class)
            expectCommandBody<Entry> {
                id.assert().isEqualTo(prepared.to)
                amount.assert().isEqualTo(prepared.amount)
            }
        }
    }
    on {
        val amountEntered = AmountEntered("sourceId", 1)
        whenEvent(amountEntered) {
            expectNoError()
            expectCommandType(Confirm::class)
            expectCommandBody<Confirm> {
                id.assert().isEqualTo(amountEntered.sourceId)
                amount.assert().isEqualTo(amountEntered.amount)
            }
        }
    }
    on {
        val entryFailed = EntryFailed("sourceId", 1)
        whenEvent(entryFailed) {
            expectCommandType(UnlockAmount::class)
            expectCommandBody<UnlockAmount> {
                id.assert().isEqualTo(entryFailed.sourceId)
                amount.assert().isEqualTo(entryFailed.amount)
            }
        }
    }
})
