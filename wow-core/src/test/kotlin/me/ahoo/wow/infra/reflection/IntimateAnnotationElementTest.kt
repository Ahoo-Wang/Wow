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
    fun `should get element`() {
        val element = Data::property.toIntimateAnnotationElement()
        element.element.assert().isEqualTo(Data::property)
    }

    @Test
    fun `should get declaring class`() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        element.declaringClass.assert().isEqualTo(Data::class)
    }

    @Test
    fun `should get property`() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        element.property.assert().isEqualTo(Data::fieldProperty)
    }

    @Test
    fun `should get getter`() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        element.getter.assert().isEqualTo(Data::fieldProperty.getter)
    }

    @Test
    fun `should get setter`() {
        val element = Data::setProperty.toIntimateAnnotationElement()
        element.setter.assert().isEqualTo(Data::setProperty.setter)
    }

    @Test
    fun `should get field`() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        element.javaField.assert().isEqualTo(Data::fieldProperty.javaField)
    }

    @Test
    fun `should get intimated annotations`() {
        val element = Data::property.toIntimateAnnotationElement()

        element.intimatedAnnotations.assert().isEqualTo(
            linkedSetOf(MockAnnotation(), MockAnnotation(), MockAnnotation())
        )
    }

    @Test
    fun `should get merged annotations`() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()

        element.inheritedAnnotations.assert().isEqualTo(
            linkedSetOf(MockAnnotation(), OnMessage(FunctionKind.EVENT, "hi"))
        )
    }

    @Test
    fun `should get intimated annotations when repeatable`() {
        val element = Data::repeatable.toIntimateAnnotationElement()
        element.intimatedAnnotations.assert().isEqualTo(linkedSetOf(MockAnnotation(), MockAnnotation()))
    }

    @Test
    fun `should get jvm intimated annotations when repeatable`() {
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
    fun `should get jvm2 intimated annotations when repeatable`() {
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
    fun `should get jvm class intimated annotations when repeatable`() {
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
    fun `should get jvm class intimated annotations when repeatable 2`() {
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
