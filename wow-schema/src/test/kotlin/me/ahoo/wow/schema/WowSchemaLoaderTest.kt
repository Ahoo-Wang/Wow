package me.ahoo.wow.schema

import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class WowSchemaLoaderTest {

    @Test
    fun loadAsStringNotFound() {
        val resourceName = "not_found.json"
        assertThrownBy<IllegalArgumentException> {
            WowSchemaLoader.loadAsString(resourceName)
        }
    }
}
