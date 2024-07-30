package me.ahoo.wow.api.query

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

    data class MockMaskingData(val pwd: String) : DataMasking<MockMaskingData> {
        override fun mask(): MockMaskingData {
            return copy(pwd = "******")
        }
    }
}
