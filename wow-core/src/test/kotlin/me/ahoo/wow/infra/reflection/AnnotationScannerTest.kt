package me.ahoo.wow.infra.reflection

import me.ahoo.wow.api.annotation.OnMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.infra.reflection.AnnotationScanner.allAnnotations
import me.ahoo.wow.infra.reflection.AnnotationScanner.intimateAnnotations
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class AnnotationScannerTest {

    @Test
    fun intimateAnnotations() {
        val annotations = Data::property.intimateAnnotations()
        assertThat(annotations.any { it.annotationClass == MockAnnotation::class }, equalTo(true))

        val getAnnotations = Data::getProperty.intimateAnnotations()
        assertThat(getAnnotations.any { it.annotationClass == MockAnnotation::class }, equalTo(true))

        val fieldAnnotations = Data::fieldProperty.intimateAnnotations()
        assertThat(fieldAnnotations.any { it.annotationClass == MockAnnotation::class }, equalTo(true))

        val setAnnotations = Data::setProperty.intimateAnnotations()
        assertThat(setAnnotations.any { it.annotationClass == MockAnnotation::class }, equalTo(true))
    }

    @Test
    fun allAnnotations() {
        val annotations = Data::property.allAnnotations()
        assertThat(annotations.any { it.annotationClass == MockAnnotation::class }, equalTo(true))
        assertThat(annotations.any { it.annotationClass == OnMessage::class }, equalTo(true))
    }

    @Test
    fun scanAnnotation() {
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

    data class Data(
        @MockAnnotation
        val property: String,
        @field:MockAnnotation
        val fieldProperty: String,
        @get:MockAnnotation
        val getProperty: String,
        @set:MockAnnotation
        var setProperty: String
    )

    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER
    )
    @OnMessage(FunctionKind.EVENT, "hi")
    annotation class MockAnnotation
}
