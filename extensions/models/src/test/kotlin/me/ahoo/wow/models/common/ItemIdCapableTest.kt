package me.ahoo.wow.models.common

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ItemIdCapableTest {

    @Test
    fun `test itemId property`() {
        val itemIdCapable = object : ItemIdCapable {
            override val itemId: String = "This is a test itemId."
        }
        itemIdCapable.itemId.assert().isEqualTo("This is a test itemId.")
    }

    @Test
    fun `test NotBlankItemIdCapable`() {
        val notBlankItemIdCapable = object : NotBlankItemIdCapable {
            override val itemId: String = "This is a test itemId."
        }
        notBlankItemIdCapable.itemId.assert().isEqualTo("This is a test itemId.")
    }
}
