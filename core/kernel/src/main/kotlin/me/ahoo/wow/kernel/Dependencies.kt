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

/**
 * Where the parameters of the generated model factories come from (decisions D49, D107): the domain services a
 * command function takes besides its command. A [ModelProvider] resolves each when the host builds the models, once,
 * at startup, so a missing one fails the start rather than a command. The port knows no DI framework: a host adapts
 * its own container (Spring's bean factory, for one), and [of] serves tests and hosts without one.
 */
fun interface Dependencies {
    /**
     * The dependency of [type] named [qualifier], or the only one of [type] when [qualifier] is null; null when
     * there is none.
     */
    fun find(type: Class<*>, qualifier: String?): Any?

    /**
     * The dependency of [type], named [qualifier] when it is given: [find]'s, or a [MissingDependencyException]
     * when there is none.
     */
    fun <T : Any> get(type: Class<T>, qualifier: String? = null): T {
        val dependency = find(type, qualifier) ?: throw MissingDependencyException(type, qualifier)
        check(type.isInstance(dependency)) {
            "The dependency of ${type.name}${describe(qualifier)} is a ${dependency.javaClass.name}."
        }
        return type.cast(dependency)
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
                    check(candidates.size <= 1) {
                        "${candidates.size} dependencies are ${type.name}: qualify the one the models take."
                    }
                    candidates.singleOrNull()
                }
            }
        }

        internal fun describe(qualifier: String?): String = qualifier?.let { " qualified \"$it\"" }.orEmpty()
    }
}

/** No dependency of [type], named [qualifier] when it is not null, to build the aggregate models with. */
class MissingDependencyException(val type: Class<*>, val qualifier: String?) :
    IllegalStateException(
        "No dependency of ${type.name}${Dependencies.describe(qualifier)} to build the aggregate models with: " +
            "register one with the host.",
    )
