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

package me.ahoo.wow.kernel

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * The full types of the generated factories' dependencies, built without reflection: the compiler writes a
 * parameterized parameter type as [parameterized] of its class and its type arguments, so [Dependencies] can tell
 * `PublishedStateCache<OrderStateView>` from `PublishedStateCache<PaymentStateView>`. The types equal the JDK's of the same
 * declaration, as [ParameterizedType] and [WildcardType] specify, so a host's container matches them.
 */
object Types {
    /** [raw] of [arguments]: `PublishedStateCache<OrderStateView>`. */
    @JvmStatic
    fun parameterized(raw: Class<*>, vararg arguments: Type): ParameterizedType {
        require(arguments.isNotEmpty()) { "A parameterized ${raw.name} has type arguments." }
        return Parameterized(raw, arguments.toList())
    }

    /** `? extends [upper]`, a covariant argument (`out T`); `?` (a star projection) when [upper] is `Object`. */
    @JvmStatic
    fun subtypeOf(upper: Type): WildcardType = Wildcard(upper, null)

    /** `? super [lower]`, a contravariant argument (`in T`). */
    @JvmStatic
    fun supertypeOf(lower: Type): WildcardType = Wildcard(Any::class.java, lower)

    /** `?`, a star projection. */
    @JvmStatic
    fun unbounded(): WildcardType = subtypeOf(Any::class.java)

    /** The class of [type]: itself for a class, its raw class for a parameterized one, `Object` for any other. */
    @JvmStatic
    fun rawOf(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as Class<*>
        is GenericArrayType -> java.lang.reflect.Array.newInstance(rawOf(type.genericComponentType), 0).javaClass
        is WildcardType -> type.upperBounds.firstOrNull()?.let(::rawOf) ?: Any::class.java
        is TypeVariable<*> -> type.bounds.firstOrNull()?.let(::rawOf) ?: Any::class.java
        else -> Any::class.java
    }

    /** [type] as Kotlin writes it: `me.ahoo.PublishedStateCache<me.ahoo.OrderStateView>`. */
    @JvmStatic
    fun nameOf(type: Type): String = when (type) {
        is Class<*> -> type.name
        else -> type.typeName
    }

    private class Parameterized(private val raw: Class<*>, private val arguments: List<Type>) : ParameterizedType {
        override fun getRawType(): Type = raw

        override fun getActualTypeArguments(): Array<Type> = arguments.toTypedArray()

        override fun getOwnerType(): Type? = raw.declaringClass

        override fun equals(other: Any?): Boolean = this === other || other is ParameterizedType &&
            raw == other.rawType && ownerType == other.ownerType && arguments == other.actualTypeArguments.toList()

        // As the JDK's: the hash of the arguments, of the owner and of the raw class, combined by XOR.
        override fun hashCode(): Int = arguments.toTypedArray().contentHashCode() xor ownerType.hashCode() xor
            raw.hashCode()

        override fun getTypeName(): String = raw.typeName + arguments.joinToString(", ", "<", ">") { it.typeName }

        override fun toString(): String = typeName
    }

    private class Wildcard(private val upper: Type, private val lower: Type?) : WildcardType {
        override fun getUpperBounds(): Array<Type> = arrayOf(upper)

        override fun getLowerBounds(): Array<Type> = lower?.let { arrayOf(it) } ?: emptyArray()

        override fun equals(other: Any?): Boolean = this === other || other is WildcardType &&
            upperBounds.contentEquals(other.upperBounds) && lowerBounds.contentEquals(other.lowerBounds)

        // As the JDK's: the hash of the lower bounds XOR that of the upper ones.
        override fun hashCode(): Int = lowerBounds.contentHashCode() xor upperBounds.contentHashCode()

        override fun getTypeName(): String = when {
            lower != null -> "? super ${lower.typeName}"
            upper == Any::class.java -> "?"
            else -> "? extends ${upper.typeName}"
        }

        override fun toString(): String = typeName
    }
}
