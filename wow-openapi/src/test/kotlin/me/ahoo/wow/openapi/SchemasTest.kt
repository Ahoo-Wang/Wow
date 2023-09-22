package me.ahoo.wow.openapi

import io.swagger.v3.core.converter.ModelConverters
import me.ahoo.wow.openapi.SchemaRef.Companion.asSchemaName
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class SchemasTest {

//    @Test
//    fun asSchemaRef() {
//        val schemaRef = MockStateAggregate::class.java.asRefSchema()
//        assertThat(
//            schemaRef.`$ref`,
//            equalTo("#/components/schemas/tck.${MockStateAggregate::class.java.simpleName}")
//        )
//    }

    @Test
    fun asSchemas() {
        DataObject::name.returnType.isMarkedNullable
        val schemas = ModelConverters.getInstance().readAll(DataObject::class.java)
        val dataObjectSchema = requireNotNull(schemas[DataObject::class.java.asSchemaName()])
        dataObjectSchema.properties.values.forEach {
            assertThat(it.readOnly, equalTo(true))
        }
        requireNotNull(dataObjectSchema.properties[DataObject::nullableName.name]).let {
            assertThat(it.type, equalTo("string"))
            assertThat(it.nullable, equalTo(true))
        }
    }
}

data class DataObject(
    val id: String,
    val name: String,
    val nullableName: String?
)
