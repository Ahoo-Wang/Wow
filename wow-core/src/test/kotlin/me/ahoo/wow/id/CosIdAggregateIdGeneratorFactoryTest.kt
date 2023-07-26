package me.ahoo.wow.id

import io.mockk.mockk
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.wow.modeling.asNamedAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CosIdAggregateIdGeneratorFactoryTest {
    private val namedAggregate = "test.test".asNamedAggregate()

    @Test
    fun create() {
        val idProvider = DefaultIdGeneratorProvider()
        val injectIdGenerator = mockk<CosIdGenerator>()
        idProvider.set("test", injectIdGenerator)
        val idGenerator = CosIdAggregateIdGeneratorFactory(idProvider).create(namedAggregate)
        assertThat(idGenerator, sameInstance(injectIdGenerator))
    }

    @Test
    fun createIfNull() {
        val idGenerator = CosIdAggregateIdGeneratorFactory().create(namedAggregate) as ClockSyncCosIdGenerator
        assertThat(idGenerator.machineId, equalTo(GlobalIdGenerator.machineId))
    }
}
