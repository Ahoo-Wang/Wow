package me.ahoo.wow.openapi

import me.ahoo.wow.openapi.SchemaRef.Companion.asRefSchema
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class SchemasTest {

    @Test
    fun asSchemaRef() {
        val schemaRef = MockStateAggregate::class.java.asRefSchema()
        assertThat(
            schemaRef.`$ref`,
            equalTo("#/components/schemas/tck.${MockStateAggregate::class.java.simpleName}")
        )
    }
}
