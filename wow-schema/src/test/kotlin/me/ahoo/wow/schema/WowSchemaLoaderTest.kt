package me.ahoo.wow.schema

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class WowSchemaLoaderTest {

    @Test
    fun loadAsStringNotFound() {
        val resourceName = "not_found.json"
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            WowSchemaLoader.loadAsString(resourceName)
        }
    }
}
