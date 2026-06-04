package me.ahoo.wow.schema

import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class WowSchemaLoaderTest {

    @Test
    fun `should throw when loading non-existent schema resource`() {
        val resourceName = "not_found.json"
        assertThrownBy<IllegalArgumentException> {
            WowSchemaLoader.loadAsString(resourceName)
        }
    }
}
