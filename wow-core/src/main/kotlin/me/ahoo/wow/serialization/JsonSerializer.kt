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
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object JsonSerializer : ObjectMapper() {

    init {
        setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
        configure(JsonParser.Feature.IGNORE_UNDEFINED, true)
        // setSerializationInclusion(JsonInclude.Include.NON_NULL)
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        registerKotlinModule()
        registerModule(JavaTimeModule())
        registerModule(WowModule())
    }
}

fun Any.asJsonString(): String {
    return JsonSerializer.writeValueAsString(this)
}

fun Any.asPrettyJson(): String {
    return JsonSerializer.writerWithDefaultPrettyPrinter().writeValueAsString(this)
}

fun <T : JsonNode> String.asJsonNode(): T {
    @Suppress("UNCHECKED_CAST")
    return JsonSerializer.readTree(this) as T
}

fun <T> String.asObject(objectType: Class<T>): T {
    return JsonSerializer.readValue(this, objectType)
}

fun <T> JsonNode.asObject(objectType: Class<T>): T {
    return JsonSerializer.treeToValue(this, objectType)
}

inline fun <reified T> String.asObject(): T {
    return asObject(T::class.java)
}

inline fun <reified T> JsonNode.asObject(): T {
    return asObject(T::class.java)
}
