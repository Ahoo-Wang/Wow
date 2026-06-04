package me.ahoo.wow.id

import io.mockk.mockk
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test

class CosIdAggregateIdGeneratorFactoryTest {
    private val namedAggregate = "test.test".toNamedAggregate()

    @Test
    fun `should create`() {
        val idProvider = DefaultIdGeneratorProvider()
        val injectIdGenerator = mockk<CosIdGenerator>()
        idProvider.set("test", injectIdGenerator)
        val idGenerator = CosIdAggregateIdGeneratorFactory(idProvider).create(namedAggregate)
        idGenerator.assert().isSameAs(injectIdGenerator)
    }

    @Test
    fun `should create if null`() {
        val idGenerator = CosIdAggregateIdGeneratorFactory().create(namedAggregate) as ClockSyncCosIdGenerator
        idGenerator.machineId.assert().isEqualTo(GlobalIdGenerator.machineId)
    }
}
