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

package me.ahoo.wow.serialization

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object JsonSerializer : ObjectMapper() {

    init {
        setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
        configure(JsonParser.Feature.IGNORE_UNDEFINED, true)
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        registerKotlinModule()
        val spiModules = findModules(this.javaClass.classLoader)
        registerModules(spiModules)
    }
}

fun Any.toJsonString(): String {
    return JsonSerializer.writeValueAsString(this)
}

fun Any.toPrettyJson(): String {
    return JsonSerializer.writerWithDefaultPrettyPrinter().writeValueAsString(this)
}

fun <T : JsonNode> Any.toJsonNode(): T {
    return JsonSerializer.valueToTree<T>(this)
}

fun <T : JsonNode> String.toJsonNode(): T {
    @Suppress("UNCHECKED_CAST")
    return JsonSerializer.readTree(this) as T
}

fun String.toObjectNode(): ObjectNode {
    return toJsonNode()
}

fun <T> String.toObject(objectType: JavaType): T {
    return JsonSerializer.readValue(this, objectType)
}

fun <T> String.toObject(objectType: Class<T>): T {
    return JsonSerializer.readValue(this, objectType)
}

fun <T> JsonNode.toObject(objectType: Class<T>): T {
    return JsonSerializer.treeToValue(this, objectType)
}

inline fun <reified T> String.toObject(): T {
    return toObject(T::class.java)
}

inline fun <reified T> JsonNode.toObject(): T {
    return toObject(T::class.java)
}

fun <T : Any> T.deepCody(objectType: Class<T> = this.javaClass): T {
    return this.toJsonString().toObject(objectType)
}
