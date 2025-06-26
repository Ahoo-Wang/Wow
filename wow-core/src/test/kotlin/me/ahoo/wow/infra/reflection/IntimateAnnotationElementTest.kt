package me.ahoo.wow.infra.reflection

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.toIntimateAnnotationElement
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.kotlinProperty

class IntimateAnnotationElementTest {

    @Test
    fun getElement() {
        val element = Data::property.toIntimateAnnotationElement()
        element.element.assert().isEqualTo(Data::property)
    }

    @Test
    fun getDeclaringClass() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        element.declaringClass.assert().isEqualTo(Data::class)
    }

    @Test
    fun getProperty() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        element.property.assert().isEqualTo(Data::fieldProperty)
    }

    @Test
    fun getGetter() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        element.getter.assert().isEqualTo(Data::fieldProperty.getter)
    }

    @Test
    fun getSetter() {
        val element = Data::setProperty.toIntimateAnnotationElement()
        element.setter.assert().isEqualTo(Data::setProperty.setter)
    }

    @Test
    fun getField() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        element.javaField.assert().isEqualTo(Data::fieldProperty.javaField)
    }

    @Test
    fun getIntimatedAnnotations() {
        val element = Data::property.toIntimateAnnotationElement()

        element.intimatedAnnotations.assert().isEqualTo(
            linkedSetOf(MockAnnotation(), MockAnnotation(), MockAnnotation())
        )
    }

    @Test
    fun getMergedAnnotations() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()

        element.inheritedAnnotations.assert().isEqualTo(
            linkedSetOf(MockAnnotation(), OnMessage(FunctionKind.EVENT, "hi"))
        )
    }

    @Test
    fun getIntimatedAnnotationsRepeatable() {
        val element = Data::repeatable.toIntimateAnnotationElement()
        element.intimatedAnnotations.assert().isEqualTo(linkedSetOf(MockAnnotation(), MockAnnotation()))
    }

    @Test
    fun getJvmIntimatedAnnotationsRepeatable() {
        val element = Data::jvmRepeatable.toIntimateAnnotationElement()

        element.intimatedAnnotations.assert().isEqualTo(
            linkedSetOf(
                JvmRepeatableTags(
                    value = arrayOf(
                        JvmRepeatableTag(value = "tag1"),
                        JvmRepeatableTag(value = "tag2")
                    )
                )
            )
        )
    }

    @Test
    fun getJvm2IntimatedAnnotationsRepeatable() {
        val element = Data::jvmRepeatable2.toIntimateAnnotationElement()

        element.intimatedAnnotations.assert().isEqualTo(
            linkedSetOf(
                JvmRepeatableTags(
                    value = arrayOf(
                        JvmRepeatableTag(value = "tag3"),
                        JvmRepeatableTag(value = "tag4")
                    )
                )
            )
        )
    }

    @Test
    fun getJvmClassIntimatedAnnotationsRepeatable() {
        val element =
            MockRepeatableClass::class.java.getDeclaredField("field").kotlinProperty!!.toIntimateAnnotationElement()

        element.intimatedAnnotations.assert().isEqualTo(
            linkedSetOf(
                JvmRepeatableTags(
                    value = arrayOf(
                        JvmRepeatableTag(value = "tag1"),
                        JvmRepeatableTag(value = "tag2")
                    )
                )
            )
        )
    }

    @Test
    fun getJvmClassIntimatedAnnotationsRepeatable2() {
        val element =
            MockRepeatableClass::class.java.getDeclaredField("field2").kotlinProperty!!.toIntimateAnnotationElement()
        element.intimatedAnnotations.assert().isEqualTo(
            linkedSetOf(
                JvmRepeatableTags(
                    value = arrayOf(
                        JvmRepeatableTag(value = "tag3"),
                        JvmRepeatableTag(value = "tag4")
                    )
                )
            )
        )
    }
}

data class Data(
    @MockAnnotation
    @get:MockAnnotation
    @field:MockAnnotation
    val property: String,
    @field:MockAnnotation
    val fieldProperty: String,
    @get:MockAnnotation
    val getProperty: String,
    @set:MockAnnotation
    var setProperty: String,
    @MockAnnotation
    @MockAnnotation
    val repeatable: String,
    @field:JvmRepeatableTag("tag1")
    @field:JvmRepeatableTag("tag2")
    val jvmRepeatable: String,
    @field:JvmRepeatableTags(
        value = [
            JvmRepeatableTag("tag3"),
            JvmRepeatableTag("tag4")
        ]
    )
    val jvmRepeatable2: String
)

@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Repeatable
@OnMessage(FunctionKind.EVENT, "hi")
annotation class MockAnnotation
