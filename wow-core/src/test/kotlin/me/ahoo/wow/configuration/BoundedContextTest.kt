package me.ahoo.wow.configuration

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class BoundedContextTest {

    @Test
    fun merge() {
        BoundedContext().merge(BoundedContext())
    }

    @Test
    fun mergeEmpty() {
        BoundedContext("").merge(BoundedContext(""))
    }

    @Test
    fun mergeEmptyNull() {
        BoundedContext("").merge(BoundedContext())
    }

    @Test
    fun mergeIfConflict() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            BoundedContext("Conflict").merge(BoundedContext("other"))
        }
    }

    @Test
    fun mergeIfNotConflict() {
        BoundedContext("alias").merge(BoundedContext("alias"))
    }
}