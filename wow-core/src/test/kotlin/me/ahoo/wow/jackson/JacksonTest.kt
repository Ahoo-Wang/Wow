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
package me.ahoo.wow.jackson

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.SuperMethodCall
import net.bytebuddy.implementation.attribute.AnnotationRetention
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * me.ahoo.wow.jackson.JacksonTest .
 *
 * @author ahoo wang
 */
class JacksonTest {
    var objectMapper = ObjectMapper()

    @Test
    fun `should serialize and deserialize type with json property`() {
        val dto = TypeWithJsonProperty("1")
        val strDto = objectMapper.writeValueAsString(dto)
        val expected = objectMapper.readValue(strDto, TypeWithJsonProperty::class.java)
        expected.assert().isEqualTo(dto)
    }

    @Disabled
    @Test
    fun `should deserialize when no default constructor and not json property`() {
        val jsonProperty = AnnotationDescription.Builder.ofType(
            JsonProperty::class.java,
        )
            .define("value", "id")
            .build()
        val typeClass = ByteBuddy()
            .with(AnnotationRetention.ENABLED)
            .subclass(Type::class.java)
            .name("ok")
            .constructor { obj: MethodDescription -> obj.isConstructor }
            .intercept(SuperMethodCall.INSTANCE)
            .annotateParameter(0, jsonProperty)
            .make()
            .load(javaClass.classLoader, ClassLoadingStrategy.Default.INJECTION)
            .loaded
        val dto = Type("1")
        val strDto = objectMapper.writeValueAsString(dto)
        val expected = objectMapper.readValue(strDto, typeClass)
        expected.assert().isEqualTo(dto)
    }

    private data class TypeWithJsonProperty constructor(@param:JsonProperty("id") val id: String)
}
