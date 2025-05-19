package me.ahoo.wow.openapi.converter

import com.fasterxml.jackson.databind.type.TypeFactory
import io.swagger.v3.core.converter.ModelConverters
import me.ahoo.cosid.stat.generator.CosIdGeneratorStat
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.query.IPagedList
import me.ahoo.wow.openapi.BatchResult
import me.ahoo.wow.openapi.context.CurrentOpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test

class ModelConvertersTest {

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

    @Test
    fun resolveBatchResultUseOpenAPIComponentContext() {
        CurrentOpenAPIComponentContext.current = OpenAPIComponentContext.default(false)
        val schemas = ModelConverters.getInstance(true).read(BatchResult::class.java)
        CurrentOpenAPIComponentContext.current = null
        schemas.assert().containsKey("wow.openapi.BatchResult")
    }

    @Test
    fun resolveListDataType() {
        CurrentOpenAPIComponentContext.current = OpenAPIComponentContext.default(true)
        val listDataType = TypeFactory.defaultInstance().constructCollectionLikeType(List::class.java, Data::class.java)
        val schemas = ModelConverters.getInstance(true).read(listDataType)
        CurrentOpenAPIComponentContext.current = null
        schemas.assert().containsKey("wow.openapi.DataList")
    }
}

data class DataPagedList(
    override val total: Long,
    override val list: List<Data>
) : IPagedList<Data>

data class Data(override val id: String) : Identifier
