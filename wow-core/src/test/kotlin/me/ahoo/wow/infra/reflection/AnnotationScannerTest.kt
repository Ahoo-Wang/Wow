package me.ahoo.wow.infra.reflection

import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class AnnotationScannerTest {

    @Test
    fun scanPropertyAnnotation() {
        val propertyAnnotation = Data::property.scanAnnotation<MockAnnotation>()
        assertThat(propertyAnnotation, notNullValue())
        assertThat(propertyAnnotation!!.annotationClass, equalTo(MockAnnotation::class))
        val propertyAnnotation2 = Data::property.scanAnnotation<MockAnnotation>()
        assertThat(propertyAnnotation2, notNullValue())
        assertThat(propertyAnnotation2!!.annotationClass, equalTo(MockAnnotation::class))
    }

    @Test
    fun scanAnnotationNotFound() {
        val propertyAnnotation = Data::class.scanAnnotation<MockAnnotation>()
        assertThat(propertyAnnotation, nullValue())
        val propertyAnnotation2 = Data::class.scanAnnotation<MockAnnotation>()
        assertThat(propertyAnnotation, nullValue())
    }

    @Test
    fun scanFunctionAnnotation() {
        val commandAnnotation = MockClass::onCommand.scanAnnotation<OnCommand>()
        assertThat(commandAnnotation, equalTo(OnCommand()))
    }

    interface MockInterface {
        @OnCommand
        fun onCommand()
    }

    class MockClass : MockInterface {
        override fun onCommand() = Unit
    }
}
