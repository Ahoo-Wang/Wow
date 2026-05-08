package me.ahoo.wow.configuration

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class BoundedContextTest {

    @Test
    fun `should merge two empty bounded contexts`() {
        val mergedContext = BoundedContext().merge(BoundedContext())
        mergedContext.alias.assert().isNull()
        mergedContext.description.assert().isEmpty()
    }

    @Test
    fun `should merge two bounded contexts with empty strings`() {
        val mergedContext = BoundedContext("").merge(BoundedContext(""))
        mergedContext.alias.assert().isEmpty()
        mergedContext.description.assert().isEmpty()
    }

    @Test
    fun `should use first context when merging with empty second`() {
        val alias = "alias"
        val description = "desc"
        val mergedContext = BoundedContext(alias, description).merge(BoundedContext())
        mergedContext.alias.assert().isEqualTo(alias)
        mergedContext.description.assert().isEqualTo(description)
    }

    @Test
    fun `should use second context when merging with empty first`() {
        val alias = "alias"
        val description = "desc"
        val mergedContext = BoundedContext().merge(BoundedContext(alias, description))
        mergedContext.alias.assert().isEqualTo(alias)
        mergedContext.description.assert().isEqualTo(description)
    }

    @Test
    fun `should throw IllegalStateException when merging with conflicting aliases`() {
        assertThrownBy<IllegalStateException> {
            BoundedContext("Conflict").merge(BoundedContext("other"))
        }
    }

    @Test
    fun `should succeed when merging with same alias`() {
        BoundedContext("alias").merge(BoundedContext("alias"))
    }
}
