package me.ahoo.wow.openapi

import me.ahoo.wow.openapi.Schemas.asSchemas
import me.ahoo.wow.openapi.Schemas.getSchemaRef
import me.ahoo.wow.openapi.route.MockCommandRoute
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class SchemasTest {

    @Test
    fun asSchemas() {
        val schemas = buildList<Class<*>?> {
            add(MockCommandRoute::class.java)
            add(null)
        }.asSchemas()
        assertThat(schemas, CoreMatchers.anything())
    }

    @Test
    fun getSchemaRef() {
        val schemaRef = MockStateAggregate::class.java.getSchemaRef()
        assertThat(
            schemaRef.`$ref`,
            CoreMatchers.equalTo("#/components/schemas/${MockStateAggregate::class.java.simpleName}")
        )
    }
}
