package me.ahoo.wow.openapi

import io.swagger.v3.core.util.Json
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaName
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemas
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
        val schemas = DataObject::class.java.toSchemas()
        val dataObjectSchema = requireNotNull(schemas[DataObject::class.java.toSchemaName()])
        requireNotNull(dataObjectSchema.properties[DataObject::nullableName.name]).let {
            assertThat(it.nullable, equalTo(true))
        }

        Json.prettyPrint(schemas)
    }
}

data class DataObject(
    val id: String,
    val nullableName: String?,
    val nestObject: NestDataObject,
    val nullableNestObject: NestDataObject?
)

data class NestDataObject(
    val id: String,
    val nullableName: String?
)
