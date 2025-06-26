package me.ahoo.wow.infra.reflection

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import org.junit.jupiter.api.Test

class AnnotationScannerTest {

    @Test
    fun scanPropertyAnnotation() {
        val propertyAnnotation = Data::property.scanAnnotation<MockAnnotation>()
        propertyAnnotation.assert().isNotNull()
        propertyAnnotation!!.annotationClass.assert().isEqualTo(MockAnnotation::class)
        val propertyAnnotation2 = Data::property.scanAnnotation<MockAnnotation>()
        propertyAnnotation2.assert().isNotNull()
        propertyAnnotation2!!.annotationClass.assert().isEqualTo(MockAnnotation::class)
    }

    @Test
    fun scanAnnotationNotFound() {
        val propertyAnnotation = Data::class.scanAnnotation<MockAnnotation>()
        propertyAnnotation.assert().isNull()
        val propertyAnnotation2 = Data::class.scanAnnotation<MockAnnotation>()
        propertyAnnotation2.assert().isNull()
    }

    @Test
    fun scanFunctionAnnotation() {
        val commandAnnotation = MockClass::onCommand.scanAnnotation<OnCommand>()
        commandAnnotation.assert().isEqualTo(OnCommand())
    }

    interface MockInterface {
        @OnCommand
        fun onCommand()
    }

    class MockClass : MockInterface {
        override fun onCommand() = Unit
    }
}
