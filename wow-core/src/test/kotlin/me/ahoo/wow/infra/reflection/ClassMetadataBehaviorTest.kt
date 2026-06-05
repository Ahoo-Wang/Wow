/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.infra.reflection

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.metadata.Metadata
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.KType

class ClassMetadataBehaviorTest {

    @Test
    fun `should visit observable class metadata in lifecycle order`() {
        val visitor = RecordingClassVisitor()

        VisitedFoundationClass::class.visit(visitor)
        val metadata = visitor.toMetadata()

        metadata.started.assert().isTrue()
        metadata.ended.assert().isTrue()
        metadata.events.first().assert().isEqualTo("start")
        metadata.events.last().assert().isEqualTo("end")
        metadata.types.any { it.classifier == VisitedFoundationClass::class }.assert().isTrue()
        metadata.properties.assert().contains("id")
        metadata.functions.assert().contains("handle")
        metadata.constructors.isNotEmpty().assert().isTrue()

        val selfTypeIndex = metadata.events.indexOf("type:VisitedFoundationClass")
        val constructorIndex = metadata.events.indexOfFirst { it.startsWith("constructor:") }
        val propertyIndex = metadata.events.indexOf("property:id")
        val functionIndex = metadata.events.indexOf("function:handle")

        selfTypeIndex.assert().isEqualTo(1)
        (constructorIndex > selfTypeIndex).assert().isTrue()
        (propertyIndex > constructorIndex).assert().isTrue()
        (functionIndex > propertyIndex).assert().isTrue()
    }
}

private data class VisitedMetadata(
    val started: Boolean,
    val ended: Boolean,
    val types: List<KType>,
    val properties: List<String>,
    val functions: List<String>,
    val constructors: List<String>,
    val events: List<String>
) : Metadata

private class RecordingClassVisitor : ClassVisitor<VisitedFoundationClass, VisitedMetadata> {
    private var started: Boolean = false
    private var ended: Boolean = false
    private val types = mutableListOf<KType>()
    private val properties = mutableListOf<String>()
    private val functions = mutableListOf<String>()
    private val constructors = mutableListOf<String>()
    private val events = mutableListOf<String>()

    override fun start() {
        started = true
        events.add("start")
    }

    override fun visitType(type: KType) {
        types.add(type)
        events.add("type:${(type.classifier as? kotlin.reflect.KClass<*>)?.simpleName ?: type}")
    }

    override fun visitProperty(property: KProperty1<VisitedFoundationClass, *>) {
        properties.add(property.name)
        events.add("property:${property.name}")
    }

    override fun visitConstructor(constructor: KFunction<*>) {
        constructors.add(constructor.name)
        events.add("constructor:${constructor.name}")
    }

    override fun visitFunction(function: KFunction<*>) {
        functions.add(function.name)
        events.add("function:${function.name}")
    }

    override fun end() {
        ended = true
        events.add("end")
    }

    override fun toMetadata(): VisitedMetadata =
        VisitedMetadata(started, ended, types, properties, functions, constructors, events)
}

private data class VisitedFoundationClass(val id: String) {
    fun handle(): String = id
}
