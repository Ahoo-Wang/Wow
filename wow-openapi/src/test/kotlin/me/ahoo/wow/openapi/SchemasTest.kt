package me.ahoo.wow.openapi

import me.ahoo.wow.openapi.Schemas.asSchemaRef
import me.ahoo.wow.openapi.Schemas.asSchemas
import me.ahoo.wow.openapi.route.MockCommandRoute
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers.anything
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class SchemasTest {
    @Test
    fun asSchemas() {
        val schemas = buildList<Class<*>?> {
            add(MockStateAggregate::class.java)
        }.asSchemas()
        assertThat(schemas, anything())
        assertThat(schemas.values.first().properties, notNullValue())
    }

    @Test
    fun asSchemasWithVariable() {
        val schemas = buildList<Class<*>?> {
            add(MockCommandRoute::class.java)
            add(null)
        }.asSchemas()
        assertThat(schemas, anything())
        assertThat(schemas.values.first().properties, nullValue())
    }

    @Test
    fun asSchemaRef() {
        val schemaRef = MockStateAggregate::class.java.asSchemaRef()
        assertThat(
            schemaRef.`$ref`,
            equalTo("#/components/schemas/${MockStateAggregate::class.java.simpleName}")
        )
    }
}
