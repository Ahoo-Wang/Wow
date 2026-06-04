package me.ahoo.wow.id

import io.mockk.mockk
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CosIdGlobalIdGeneratorFactoryTest {

    @Test
    fun `should create`() {
        val idProvider = DefaultIdGeneratorProvider()
        idProvider.set(CosIdGlobalIdGeneratorFactory.ID_NAME, mockk<CosIdGenerator>())
        val idGenerator = CosIdGlobalIdGeneratorFactory(idProvider).create()
        idGenerator.assert().isNotNull()
    }

    @Test
    fun `should create if null`() {
        val idGenerator = CosIdGlobalIdGeneratorFactory().create()
        idGenerator.assert().isNull()
    }
}
