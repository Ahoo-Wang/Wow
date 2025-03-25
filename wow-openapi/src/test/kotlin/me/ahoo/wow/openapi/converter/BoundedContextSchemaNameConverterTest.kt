package me.ahoo.wow.openapi.converter

import io.swagger.v3.core.converter.ModelConverters
import me.ahoo.cosid.stat.generator.CosIdGeneratorStat
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class BoundedContextSchemaNameConverterTest {

    @Test
    fun resolveAggregate() {
        val schemas = ModelConverters.getInstance(true).read(MockCommandAggregate::class.java)
        assertThat(schemas.containsKey("tck.mock_aggregate.MockCommandAggregate"), equalTo(true))
    }

    @Test
    fun resolveJavaLib() {
        val schemas = ModelConverters.getInstance(true).read(String::class.java)
        assertThat(schemas.size, equalTo(0))
    }

    @Test
    fun resolveJavaClass() {
        val schemas = ModelConverters.getInstance(true).read(CosIdGeneratorStat::class.java)
        assertThat(schemas.containsKey("CosIdGeneratorStat"), equalTo(true))
    }
}
