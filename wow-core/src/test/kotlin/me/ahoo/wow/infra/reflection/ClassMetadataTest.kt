package me.ahoo.wow.infra.reflection

import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import org.junit.jupiter.api.Test

class ClassMetadataTest {

    @Test
    fun visit() {
        MockClass::class.visit(
            object :
                ClassVisitor<MockClass> {
            },
        )
    }

    class MockClass(val id: String, var name: String) {
        private fun privateFun() = Unit
        fun publicFun() = Unit
    }
}
