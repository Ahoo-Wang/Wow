package me.ahoo.wow.configuration

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class BoundedContextTest {

    @Test
    fun merge() {
        val mergedContext = BoundedContext().merge(BoundedContext())
        mergedContext.alias.assert().isNull()
        mergedContext.description.assert().isEmpty()
    }

    @Test
    fun mergeEmpty() {
        val mergedContext = BoundedContext("").merge(BoundedContext(""))
        mergedContext.alias.assert().isEmpty()
        mergedContext.description.assert().isEmpty()
    }

    @Test
    fun mergeIfFirstNotEmpty() {
        val alias = "alias"
        val description = "desc"
        val mergedContext = BoundedContext(alias, description).merge(BoundedContext())
        mergedContext.alias.assert().isEqualTo(alias)
        mergedContext.description.assert().isEqualTo(description)
    }

    @Test
    fun mergeIfSecondNotEmpty() {
        val alias = "alias"
        val description = "desc"
        val mergedContext = BoundedContext().merge(BoundedContext(alias, description))
        mergedContext.alias.assert().isEqualTo(alias)
        mergedContext.description.assert().isEqualTo(description)
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
