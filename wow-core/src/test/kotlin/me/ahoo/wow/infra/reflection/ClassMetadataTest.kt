package me.ahoo.wow.infra.reflection

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.metadata.Metadata
import org.junit.jupiter.api.Test

class ClassMetadataTest {

    @Test
    fun visit() {
        val visitor = object :
            ClassVisitor<MockClass, Metadata> {
            override fun toMetadata(): Metadata {
                return MockMetadata()
            }
        }
        MockClass::class.visit(
            visitor
        )
        visitor.toMetadata().assert().isInstanceOf(MockMetadata::class.java)
    }

    class MockClass(val id: String, var name: String) {
        private fun privateFun() = Unit
        fun publicFun() = Unit
    }

    class MockMetadata : Metadata
}
