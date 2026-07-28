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

package me.ahoo.wow.infra.batch

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class BatchAdmissionTest {

    @Test
    fun `reclaimed reservation cannot be transferred or released twice`() {
        val admission = BatchAdmission<Int>(capacity = 1)
        val reservation = admission.tryReserve()!!
        admission.tryReserve().assert().isNull()

        admission.releaseReservations()

        admission.reservationCount.assert().isZero()
        reservation.track(1).assert().isNull()
        reservation.release()

        val nextReservation = admission.tryReserve()!!
        admission.tryReserve().assert().isNull()
        val request = nextReservation.track(2)!!
        request.discardAdmission()

        admission.tryReserve()!!.release()
    }
}
