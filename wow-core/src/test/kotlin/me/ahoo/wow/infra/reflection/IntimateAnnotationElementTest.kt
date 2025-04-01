package me.ahoo.wow.infra.reflection

import me.ahoo.wow.api.annotation.OnMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.toIntimateAnnotationElement
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.kotlinProperty

class IntimateAnnotationElementTest {

    @Test
    fun getElement() {
        val element = Data::property.toIntimateAnnotationElement()
        assertThat(element.element, equalTo(Data::property))
    }

    @Test
    fun getDeclaringClass() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        assertThat(element.declaringClass, equalTo(Data::class))
    }

    @Test
    fun getProperty() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        assertThat(element.property, equalTo(Data::fieldProperty))
    }

    @Test
    fun getGetter() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        assertThat(element.getter, equalTo(Data::fieldProperty.getter))
    }

    @Test
    fun getSetter() {
        val element = Data::setProperty.toIntimateAnnotationElement()
        assertThat(element.setter, equalTo(Data::setProperty.setter))
    }

    @Test
    fun getField() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        assertThat(element.javaField, equalTo(Data::fieldProperty.javaField))
    }

    @Test
    fun getIntimatedAnnotations() {
        val element = Data::property.toIntimateAnnotationElement()
        assertThat(
            element.intimatedAnnotations,
            equalTo(linkedSetOf(MockAnnotation(), MockAnnotation(), MockAnnotation()))
        )
    }

    @Test
    fun getMergedAnnotations() {
        val element = Data::fieldProperty.toIntimateAnnotationElement()
        assertThat(
            element.inheritedAnnotations,
            equalTo(linkedSetOf(MockAnnotation(), OnMessage(FunctionKind.EVENT, "hi")))
        )
    }

    @Test
    fun getIntimatedAnnotationsRepeatable() {
        val element = Data::repeatable.toIntimateAnnotationElement()
        assertThat(element.intimatedAnnotations, equalTo(linkedSetOf(MockAnnotation(), MockAnnotation())))
    }

    @Test
    fun getJvmIntimatedAnnotationsRepeatable() {
        val element = Data::jvmRepeatable.toIntimateAnnotationElement()
        assertThat(
            element.intimatedAnnotations,
            equalTo(
                linkedSetOf(
                    JvmRepeatableTags(
                        value = arrayOf(
                            JvmRepeatableTag(value = "tag1"),
                            JvmRepeatableTag(value = "tag2")
                        )
                    )
                )
            )
        )
    }

    @Test
    fun getJvm2IntimatedAnnotationsRepeatable() {
        val element = Data::jvmRepeatable2.toIntimateAnnotationElement()
        assertThat(
            element.intimatedAnnotations,
            equalTo(
                linkedSetOf(
                    JvmRepeatableTags(
                        value = arrayOf(
                            JvmRepeatableTag(value = "tag3"),
                            JvmRepeatableTag(value = "tag4")
                        )
                    )
                )
            )
        )
    }

    @Test
    fun getJvmClassIntimatedAnnotationsRepeatable() {
        val element =
            MockRepeatableClass::class.java.getDeclaredField("field").kotlinProperty!!.toIntimateAnnotationElement()
        assertThat(
            element.intimatedAnnotations,
            equalTo(
                linkedSetOf(
                    JvmRepeatableTags(
                        value = arrayOf(
                            JvmRepeatableTag(value = "tag1"),
                            JvmRepeatableTag(value = "tag2")
                        )
                    )
                )
            )
        )
    }

    @Test
    fun getJvmClassIntimatedAnnotationsRepeatable2() {
        val element =
            MockRepeatableClass::class.java.getDeclaredField("field2").kotlinProperty!!.toIntimateAnnotationElement()
        assertThat(
            element.intimatedAnnotations,
            equalTo(
                linkedSetOf(
                    JvmRepeatableTags(
                        value = arrayOf(
                            JvmRepeatableTag(value = "tag3"),
                            JvmRepeatableTag(value = "tag4")
                        )
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
