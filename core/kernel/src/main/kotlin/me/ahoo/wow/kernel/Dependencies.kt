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

import java.lang.reflect.Type

/**
 * Where the parameters of the generated model factories come from: the domain services a
 * command function takes besides its command. A [ModelProvider] resolves each when the host builds the models, once,
 * at startup, so a missing one fails the start rather than a command. The port knows no DI framework: a host adapts
 * its own container (Spring's bean factory, for one), and [of] serves tests and hosts without one.
 *
 * A parameter of a parameterized type (`PublishedStateCache<OrderStateView>`) is looked up by its full type ([Types]), so
 * two dependencies of one class and different type arguments are told apart; a host that knows no generics finds it
 * by its class, the default.
 */
fun interface Dependencies {
    /**
     * The dependency of [type] named [qualifier], or the only one of [type] when [qualifier] is null; null when
     * there is none.
     */
    fun find(type: Class<*>, qualifier: String?): Any?

    /**
     * The dependency of the full [type], a parameterized one, named [qualifier], or the only one of [type] when
     * [qualifier] is null; null when there is none. By its class ([Types.rawOf]) by default, for a host that knows no
     * generics; a host that does finds it by every type argument.
     *
     * @throws AmbiguousDependencyException when several are of [type] and none is the one to take.
     */
    fun find(type: Type, qualifier: String?): Any? = find(Types.rawOf(type), qualifier)

    /**
     * The dependency of [type], named [qualifier] when it is given, to build [building] with (a processor, `the Order
     * model`): [find]'s, or a [MissingDependencyException] naming what it builds when there is none.
     */
    fun <T : Any> get(type: Class<T>, qualifier: String? = null, building: String? = null): T {
        val dependency = find(type, qualifier) ?: throw MissingDependencyException(type, qualifier, building)
        check(type.isInstance(dependency)) {
            "The dependency of ${type.name}${describe(qualifier)} is a ${dependency.javaClass.name}."
        }
        return type.cast(dependency)
    }

    /**
     * The dependency of the full [type], whose class is [raw], for the [parameter] of what it builds, [building]:
     * [find]'s by the full type, or a [MissingDependencyException] naming the type when there is none; an
     * [AmbiguousDependencyException] naming the parameter when several are of it.
     */
    fun <T : Any> get(raw: Class<T>, type: Type, qualifier: String?, building: String?, parameter: String): T {
        val dependency = try {
            find(type, qualifier)
        } catch (ambiguous: AmbiguousDependencyException) {
            throw ambiguous.of(building, parameter)
        } ?: throw MissingDependencyException(raw, qualifier, building, type)
        check(raw.isInstance(dependency)) {
            "The dependency of ${Types.nameOf(type)}${describe(qualifier)} is a ${dependency.javaClass.name}."
        }
        return raw.cast(dependency)
    }

    companion object {
        /** No dependency at all, for modules whose models take none. */
        @JvmField
        val NONE: Dependencies = Dependencies { _, _ -> null }

        /** The [instances], found by type; a type two of them have is ambiguous. */
        @JvmStatic
        fun of(vararg instances: Any): Dependencies = of(emptyMap(), *instances)

        /**
         * The [qualified] instances, found by their qualifier, and the [instances], found by type; a type two of
         * [instances] have is ambiguous, so one of them needs a qualifier.
         */
        @JvmStatic
        fun of(qualified: Map<String, Any>, vararg instances: Any): Dependencies {
            val unqualified = instances.toList()
            return Dependencies { type, qualifier ->
                if (qualifier != null) {
                    qualified[qualifier]?.takeIf { type.isInstance(it) }
                } else {
                    val candidates = unqualified.filter { type.isInstance(it) }
                    if (candidates.size > 1) {
                        throw AmbiguousDependencyException(type, candidates.map { it.javaClass.name })
                    }
                    candidates.singleOrNull()
                }
            }
        }

        internal fun describe(qualifier: String?): String = qualifier?.let { " qualified \"$it\"" }.orEmpty()
    }
}

/**
 * No dependency of [type], named [qualifier] when it is not null, to build [building] with: a processor
 * (`OrderSummaryProjection`) or a model (`the Order model`), the generated code's models and processors when it is
 * null.
 */
class MissingDependencyException(
    val type: Class<*>,
    val qualifier: String?,
    val building: String? = null,
    /** The full type looked for, when it is a parameterized one. */
    val fullType: Type = type,
) : IllegalStateException(
    "No dependency of ${Types.nameOf(fullType)}${Dependencies.describe(qualifier)} to build " +
        "${building ?: "the models and processors"} with: register one with the host.",
)

/**
 * Several dependencies are of [type], the [candidates] by their names, and none is the one to take: the [parameter]
 * of [building] names one with a qualifier (`@Name`), or the host marks one primary.
 */
class AmbiguousDependencyException(
    val type: Type,
    val candidates: List<String>,
    val building: String? = null,
    val parameter: String? = null,
) : IllegalStateException(
    "${candidates.size} dependencies are ${Types.nameOf(type)} (${candidates.joinToString()}) and none is primary" +
        (parameter?.let { ": the parameter $it" }.orEmpty()) + (building?.let { " of $it" }.orEmpty()) +
        " takes one; qualify it with @Name.",
) {
    /** This ambiguity, of the [parameter] of [building]. */
    fun of(building: String?, parameter: String): AmbiguousDependencyException =
        AmbiguousDependencyException(type, candidates, building, parameter).also { it.initCause(this) }
}
