package me.ahoo.wow.infra.reflection

import me.ahoo.wow.infra.reflection.KClassMetadata.visit
import org.junit.jupiter.api.Test

class KClassMetadataTest {

    @Test
    fun visit() {
        MockClass::class.visit(
            object :
                KClassVisitor<MockClass> {
            },
        )
    }

    class MockClass(val id: String, var name: String) {
        private fun privateFun() = Unit
        fun publicFun() = Unit
    }
}
