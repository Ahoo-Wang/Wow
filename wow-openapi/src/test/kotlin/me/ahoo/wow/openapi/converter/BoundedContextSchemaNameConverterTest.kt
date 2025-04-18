package me.ahoo.wow.openapi.converter

import io.swagger.v3.core.converter.ModelConverters
import me.ahoo.cosid.stat.generator.CosIdGeneratorStat
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.BatchResult
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test

class BoundedContextSchemaNameConverterTest {

    @Test
    fun resolveAggregate() {
        val schemas = ModelConverters.getInstance(true).read(MockCommandAggregate::class.java)
        schemas.assert().containsKey("tck.mock_aggregate.MockCommandAggregate")
    }

    @Test
    fun resolveJavaLib() {
        val schemas = ModelConverters.getInstance(true).read(String::class.java)
        schemas.assert().hasSize(0)
    }

    @Test
    fun resolveJavaClass() {
        val schemas = ModelConverters.getInstance(true).read(CosIdGeneratorStat::class.java)
        schemas.assert().containsKey("CosIdGeneratorStat")
    }

    @Test
    fun resolveBatchResult() {
        val schemas = ModelConverters.getInstance(true).read(BatchResult::class.java)
        schemas.assert().containsKey("wow.openapi.BatchResult")
    }
}
