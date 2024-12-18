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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class DataMaskingKtTest {

    @Test
    fun tryMask() {
        val snapshot = MaterializedSnapshot(
            contextName = "contextName",
            aggregateName = "aggregateName",
            tenantId = "tenantId",
            aggregateId = "aggregateId",
            version = 1,
            eventId = "eventId",
            firstOperator = "firstOperator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            state = "state",
            snapshotTime = 1,
            deleted = false
        )
        val masked = snapshot.tryMask()
        assertThat(masked, sameInstance(snapshot))
    }

    @Test
    fun tryMaskIfMaskable() {
        val snapshot = MaterializedSnapshot(
            contextName = "contextName",
            aggregateName = "aggregateName",
            tenantId = "tenantId",
            aggregateId = "aggregateId",
            version = 1,
            eventId = "eventId",
            firstOperator = "firstOperator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            state = MockMaskingData("pwd"),
            snapshotTime = 1,
            deleted = false
        )
        val masked = snapshot.tryMask()
        assertThat(masked.state.pwd, equalTo("******"))
    }

    @Test
    fun pagedListTryMask() {
        val snapshot = MaterializedSnapshot(
            contextName = "contextName",
            aggregateName = "aggregateName",
            tenantId = "tenantId",
            aggregateId = "aggregateId",
            version = 1,
            eventId = "eventId",
            firstOperator = "firstOperator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            state = "state",
            snapshotTime = 1,
            deleted = false
        )
        val pagedList = PagedList(1, listOf(snapshot))
        val masked = pagedList.tryMask()
        assertThat(masked, sameInstance(pagedList))
    }

    @Test
    fun pagedListTryMaskIfMaskable() {
        val snapshot = MaterializedSnapshot(
            contextName = "contextName",
            aggregateName = "aggregateName",
            tenantId = "tenantId",
            aggregateId = "aggregateId",
            version = 1,
            eventId = "eventId",
            firstOperator = "firstOperator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            state = MockMaskingData("pwd"),
            snapshotTime = 1,
            deleted = false
        )
        val pagedList = PagedList(1, listOf(snapshot))
        val masked = pagedList.tryMask()
        assertThat(masked.list.first().state.pwd, equalTo("******"))
    }

    @Test
    fun tryMaskState() {
        val state = MockMaskingData("pwd")
        val masked = state.tryMask()
        assertThat(masked.pwd, equalTo("******"))
    }

    @Test
    fun tryMaskAnyState() {
        val source = Any()
        val masked = source.tryMask()
        assertThat(masked, sameInstance(source))
    }

    data class MockMaskingData(val pwd: String) : DataMasking<MockMaskingData> {
        override fun mask(): MockMaskingData {
            return copy(pwd = "******")
        }
    }
}
