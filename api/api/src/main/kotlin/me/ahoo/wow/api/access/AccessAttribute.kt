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

package me.ahoo.wow.api.access

/**
 * A reference to an access attribute of the aggregate whose state is [S]: a [Single] value or [Multiple] ones of
 * [type], under its published [name]. The state declares its attributes with `@Access`, and the compiler generates a
 * reference to each in `<State>Access` ([AccessAttributes]); [OWNER] and [SPACE] are the built-in ones every aggregate
 * has, its scope's, so they are references of every state (`S` is `in`: a reference of `Any` is one of each state).
 * An access policy of a state takes the references of that state alone, which the compiler checks.
 *
 * [type] is the class of each value: `String`, `Integer`, `Long`, `Boolean` or an enum class ([isValueType]); the
 * name follows the rule the compiler and the Manifest hold it to (`me.ahoo.wow.manifest.AccessAttributeName` of
 * `:manifest`). Two references are equal when they name the same attribute alike.
 *
 * No case is added within 10.x, so a `when` over it may name every case.
 */
sealed class AccessAttribute<in S : Any, V : Any>(val name: String, val type: Class<V>) {
    /** Whether the attribute holds several values: [Multiple]. */
    abstract val multiple: Boolean

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AccessAttribute<*, *> && name == other.name && type == other.type && multiple == other.multiple

    override fun hashCode(): Int = listOf(name, type, multiple).hashCode()

    override fun toString(): String = "AccessAttribute(name=$name, type=${type.name}, multiple=$multiple)"

    /** An attribute of one value, or none. */
    class Single<in S : Any, V : Any> internal constructor(name: String, type: Class<V>) :
        AccessAttribute<S, V>(name, type) {
        override val multiple: Boolean get() = false
    }

    /** An attribute of several values, a set of them. */
    class Multiple<in S : Any, V : Any> internal constructor(name: String, type: Class<V>) :
        AccessAttribute<S, V>(name, type) {
        override val multiple: Boolean get() = true
    }

    companion object {
        /** The aggregate's owner, its scope's: none for an aggregate of its tenant. */
        @JvmField
        val OWNER: Single<Any, String> = Single("owner", String::class.java)

        /** The aggregate's space, its scope's. */
        @JvmField
        val SPACE: Single<Any, String> = Single("space", String::class.java)

        /** The names of the built-in attributes, which no declared attribute takes. */
        @JvmField
        val BUILT_IN: Set<String> = setOf(OWNER.name, SPACE.name)

        private val VALUE_TYPES: Set<Class<*>> = setOf(
            String::class.java,
            Int::class.javaObjectType,
            Long::class.javaObjectType,
            Boolean::class.javaObjectType,
        )

        /** Whether an attribute's values may be of [type]: `String`, `Integer`, `Long`, `Boolean` or an enum class. */
        @JvmStatic
        fun isValueType(type: Class<*>): Boolean = type in VALUE_TYPES || type.isEnum

        /** The attribute [name] of state [S], of one value of [type]; what the generated `<State>Access` holds. */
        @JvmStatic
        fun <S : Any, V : Any> single(name: String, type: Class<V>): Single<S, V> = Single(declared(name, type), type)

        /** The attribute [name] of state [S], of several values of [type]; what the generated `<State>Access` holds. */
        @JvmStatic
        fun <S : Any, V : Any> multiple(name: String, type: Class<V>): Multiple<S, V> =
            Multiple(declared(name, type), type)

        private fun declared(name: String, type: Class<*>): String {
            require(name !in BUILT_IN) { "The access attribute $name is built in." }
            require(isValueType(type)) {
                "The access attribute $name holds ${type.name}: a String, Int, Long, Boolean or enum is required."
            }
            return name
        }
    }
}

/**
 * What the generated `<State>Access` of the state [S] extends: the built-in attributes beside the state's own, which
 * it names in upper snake case after their names, as the query fields are named
 * (`DocumentStateAccess.SHARED_WITH` is `sharedWith`).
 *
 * ```kotlin
 * public object DocumentStateAccess : AccessAttributes<DocumentState>() {
 *     public val DEPARTMENT: AccessAttribute.Single<DocumentState, String> =
 *         AccessAttribute.single("department", String::class.java)
 * }
 * ```
 */
@Suppress("PropertyName", "VariableNaming")
abstract class AccessAttributes<S : Any> protected constructor() {
    /** The aggregate's owner: [AccessAttribute.OWNER]. */
    @JvmField
    val OWNER: AccessAttribute.Single<S, String> = AccessAttribute.OWNER

    /** The aggregate's space: [AccessAttribute.SPACE]. */
    @JvmField
    val SPACE: AccessAttribute.Single<S, String> = AccessAttribute.SPACE
}
