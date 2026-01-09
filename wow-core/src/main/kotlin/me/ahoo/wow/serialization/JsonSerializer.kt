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
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * Pre-configured Jackson [ObjectMapper] for the Wow framework.
 *
 * This singleton provides a ready-to-use JSON serializer/deserializer with optimized settings
 * for Kotlin classes and Wow-specific modules. It includes configurations for:
 * - Field visibility set to ANY for all property accessors
 * - Ignoring undefined JSON parser features
 * - Disabling failure on unknown properties during deserialization
 * - Kotlin module registration for better Kotlin support
 * - Automatic registration of SPI-discovered Jackson modules
 *
 * Example usage:
 * ```kotlin
 * data class User(val name: String, val age: Int)
 * val user = User("John", 30)
 * val json = JsonSerializer.writeValueAsString(user)
 * val deserialized = JsonSerializer.readValue(json, User::class.java)
 * ```
 *
 * @see ObjectMapper for base Jackson functionality
 */
object JsonSerializer : ObjectMapper() {
    init {
        setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
        configure(JsonParser.Feature.IGNORE_UNDEFINED, true)
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        registerKotlinModule()
        findAndRegisterModules()
    }
}

/**
 * Converts this object to its JSON string representation.
 *
 * Uses the pre-configured [JsonSerializer] to serialize the object.
 *
 * @receiver The object to serialize. Can be any type.
 * @return The JSON string representation of the object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if serialization fails.
 *
 * Example:
 * ```kotlin
 * val user = mapOf("name" to "John", "age" to 30)
 * val json = user.toJsonString() // {"name":"John","age":30}
 * ```
 */
fun Any.toJsonString(): String = JsonSerializer.writeValueAsString(this)

private val DEFAULT_PRETTY_WRITER = JsonSerializer.writerWithDefaultPrettyPrinter()

/**
 * Converts this object to a pretty-printed JSON string representation.
 *
 * Uses the pre-configured [JsonSerializer] with default pretty printer for formatted output.
 *
 * @receiver The object to serialize. Can be any type.
 * @return The pretty-printed JSON string representation of the object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if serialization fails.
 *
 * Example:
 * ```kotlin
 * val user = mapOf("name" to "John", "age" to 30)
 * val json = user.toPrettyJson()
 * // {
 * //   "name" : "John",
 * //   "age" : 30
 * // }
 * ```
 */
fun Any.toPrettyJson(): String = DEFAULT_PRETTY_WRITER.writeValueAsString(this)

/**
 * Converts this object to a [JsonNode] representation.
 *
 * Uses the pre-configured [JsonSerializer] to convert the object to a tree structure.
 *
 * @param T The specific type of [JsonNode] to return (e.g., [ObjectNode], [ArrayNode]).
 * @receiver The object to convert. Can be any type.
 * @return The [JsonNode] representation of the object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * val user = mapOf("name" to "John", "age" to 30)
 * val node = user.toJsonNode<ObjectNode>()
 * val name = node["name"].asText() // "John"
 * ```
 */
fun <T : JsonNode> Any.toJsonNode(): T = JsonSerializer.valueToTree<T>(this)

/**
 * Parses this JSON string into a [JsonNode] representation.
 *
 * Uses the pre-configured [JsonSerializer] to parse the JSON string.
 *
 * @param T The specific type of [JsonNode] to return (e.g., [ObjectNode], [ArrayNode]).
 * @receiver The JSON string to parse.
 * @return The parsed [JsonNode].
 * @throws com.fasterxml.jackson.core.JsonProcessingException if parsing fails.
 *
 * Example:
 * ```kotlin
 * val json = """{"name":"John","age":30}"""
 * val node = json.toJsonNode<ObjectNode>()
 * val name = node["name"].asText() // "John"
 * ```
 */
fun <T : JsonNode> String.toJsonNode(): T {
    @Suppress("UNCHECKED_CAST")
    return JsonSerializer.readTree(this) as T
}

/**
 * Parses this JSON string into an [ObjectNode].
 *
 * Convenience method that parses the string and casts to [ObjectNode].
 *
 * @receiver The JSON string to parse.
 * @return The parsed [ObjectNode].
 * @throws com.fasterxml.jackson.core.JsonProcessingException if parsing fails.
 * @throws ClassCastException if the parsed JSON is not an object.
 *
 * Example:
 * ```kotlin
 * val json = """{"name":"John","age":30}"""
 * val node = json.toObjectNode()
 * val name = node["name"].asText() // "John"
 * ```
 */
fun String.toObjectNode(): ObjectNode = toJsonNode()

/**
 * Deserializes this JSON string into an object of the specified [JavaType].
 *
 * Uses the pre-configured [JsonSerializer] to read the value.
 *
 * @param T The type of object to deserialize to.
 * @param objectType The [JavaType] representing the target type.
 * @receiver The JSON string to deserialize.
 * @return The deserialized object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if deserialization fails.
 *
 * Example:
 * ```kotlin
 * val json = """["John","Jane"]"""
 * val type = JsonSerializer.typeFactory.constructCollectionType(List::class.java, String::class.java)
 * val list = json.toObject<List<String>>(type)
 * ```
 */
fun <T> String.toObject(objectType: JavaType): T = JsonSerializer.readValue(this, objectType)

/**
 * Deserializes this JSON string into an object of the specified [Class].
 *
 * Uses the pre-configured [JsonSerializer] to read the value.
 *
 * @param T The type of object to deserialize to.
 * @param objectType The [Class] representing the target type.
 * @receiver The JSON string to deserialize.
 * @return The deserialized object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if deserialization fails.
 *
 * Example:
 * ```kotlin
 * data class User(val name: String, val age: Int)
 * val json = """{"name":"John","age":30}"""
 * val user = json.toObject<User>(User::class.java)
 * ```
 */
fun <T> String.toObject(objectType: Class<T>): T = JsonSerializer.readValue(this, objectType)

/**
 * Converts this [JsonNode] into an object of the specified [Class].
 *
 * Uses the pre-configured [JsonSerializer] to convert the tree to value.
 *
 * @param T The type of object to convert to.
 * @param objectType The [Class] representing the target type.
 * @receiver The [JsonNode] to convert.
 * @return The converted object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * val json = """{"name":"John","age":30}"""
 * val node = json.toJsonNode<ObjectNode>()
 * val user = node.toObject<User>(User::class.java)
 * ```
 */
fun <T> JsonNode.toObject(objectType: Class<T>): T = JsonSerializer.treeToValue(this, objectType)

/**
 * Deserializes this JSON string into an object using reified generics.
 *
 * Convenience method that uses the reified type parameter to avoid specifying the class explicitly.
 *
 * @param T The type of object to deserialize to, inferred from the call site.
 * @receiver The JSON string to deserialize.
 * @return The deserialized object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if deserialization fails.
 *
 * Example:
 * ```kotlin
 * data class User(val name: String, val age: Int)
 * val json = """{"name":"John","age":30}"""
 * val user = json.toObject<User>()
 * ```
 */
inline fun <reified T> String.toObject(): T = toObject(T::class.java)

/**
 * Converts this [JsonNode] into an object using reified generics.
 *
 * Convenience method that uses the reified type parameter to avoid specifying the class explicitly.
 *
 * @param T The type of object to convert to, inferred from the call site.
 * @receiver The [JsonNode] to convert.
 * @return The converted object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * val json = """{"name":"John","age":30}"""
 * val node = json.toJsonNode<ObjectNode>()
 * val user = node.toObject<User>()
 * ```
 */
inline fun <reified T> JsonNode.toObject(): T = toObject(T::class.java)

/**
 * Converts this object to the specified target type.
 *
 * Uses the pre-configured [JsonSerializer] to convert the object.
 *
 * @param T The type of object to convert to.
 * @param targetType The [Class] representing the target type.
 * @receiver The object to convert.
 * @return The converted object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * data class User(val name: String, val age: Int)
 * data class UserDto(val userName: String, val userAge: Int)
 * val user = User("John", 30)
 * val dto = user.convert(UserDto::class.java)
 * ```
 */
fun <T> Any.convert(targetType: Class<T>): T = JsonSerializer.convertValue(this, targetType)

/**
 * Converts this object to the specified target type.
 *
 * Uses the pre-configured [JsonSerializer] to convert the object.
 *
 * @param T The type of object to convert to.
 * @param targetType The [JavaType] representing the target type.
 * @receiver The object to convert.
 * @return The converted object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * val json = """{"name":"John","age":30}"""
 * val node = json.toJsonNode<ObjectNode>()
 * val type = JsonSerializer.typeFactory.constructMapType(HashMap::class.java, String::class.java, Any::class.java)
 * val map = node.convert<MutableMap<String, Any>>(type)
 * ```
 */
fun <T> Any.convert(targetType: JavaType): T = JsonSerializer.convertValue<T>(this, targetType)

/**
 * Converts this object to the specified target type.
 *
 * Uses the pre-configured [JsonSerializer] to convert the object.
 *
 * @param T The type of object to convert to.
 * @param targetType The [TypeReference] representing the target type.
 * @receiver The object to convert.
 * @return The converted object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * data class User(val name: String, val addresses: List<Address>)
 * val user = User("John", listOf(Address("123 Main St")))
 * val typeRef = object : TypeReference<User>() {}
 * val converted = user.convert(typeRef)
 * ```
 */
fun <T> Any.convert(targetType: TypeReference<T>): T = JsonSerializer.convertValue<T>(this, targetType)

/**
 * Converts this object to the specified target type using reified generics.
 *
 * Convenience method that uses the reified type parameter to avoid specifying the class explicitly.
 *
 * @param T The type of object to convert to, inferred from the call site.
 * @receiver The object to convert.
 * @return The converted object.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * data class User(val name: String, val age: Int)
 * data class UserDto(val userName: String, val userAge: Int)
 * val user = User("John", 30)
 * val dto = user.convert<UserDto>()
 * ```
 */
inline fun <reified T> Any.convert(): T = JsonSerializer.convertValue<T>(this, T::class.java)

/**
 * Converts this object to an instance of the specified target type.
 *
 * Uses Jackson's [convertValue] to perform type conversion, which maps properties
 * between the source and target objects. The target type must have compatible property
 * names and types for successful conversion.
 *
 * This method is useful for:
 * - Converting between data classes with similar structures
 * - Creating copies with a different type
 * - Transforming object representations
 *
 * @param T The type of the object being converted.
 * @param targetType The [Class] representing the target type. Defaults to the object's class.
 * @receiver The object to convert.
 * @return A new instance of the target type with mapped properties.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * data class User(val name: String, val age: Int)
 * data class UserDto(val name: String, val age: Int)
 * val user = User("John", 30)
 * val dto = user.deepCopy<UserDto>()
 * ```
 */
fun <T : Any> T.deepCopy(targetType: Class<T> = this.javaClass): T = this.convert(targetType)

private val MAP_TYPE_REF = object : TypeReference<LinkedHashMap<String, Any>>() {}

/**
 * Converts this object to a [MutableMap] representation.
 *
 * Uses the pre-configured [JsonSerializer] to convert the object to a map with [String] keys and [Any] values.
 * This is useful for generic access to object properties or for serialization purposes.
 *
 * @param T The type of the object being converted.
 * @receiver The object to convert to a map.
 * @return A [MutableMap] containing all properties of the object with their string keys.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if conversion fails.
 *
 * Example:
 * ```kotlin
 * data class User(val name: String, val age: Int)
 * val user = User("John", 30)
 * val map = user.toMap()
 * println(map["name"]) // "John"
 * println(map["age"]) // 30
 * ```
 */
fun <T : Any> T.toMap(): MutableMap<String, Any> = this.convert(MAP_TYPE_REF)
