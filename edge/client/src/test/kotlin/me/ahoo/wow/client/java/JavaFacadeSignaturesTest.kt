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

package me.ahoo.wow.client.java

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * The Java facade (decision D260) shows Java nothing of Kotlin: no public method or constructor of its classes takes
 * or returns a `kotlin.*` type (a `Duration`, a `Flow`, a function type, a `Continuation` of a `suspend`
 * function), at any depth of its generic signature, and none has a wildcard that Kotlin's variance would add
 * (`Snapshot<? extends S>`).
 */
class JavaFacadeSignaturesTest {
    private val facade = listOf(
        JavaCommandClient::class.java,
        JavaCommandClient.Options::class.java,
        JavaQueryApi::class.java,
        JavaQueries::class.java,
        JavaTrustedQueries::class.java,
        JavaRowQueries::class.java,
        JavaModelQueries::class.java,
        JavaSnapshotQueries::class.java,
    )

    /** Every `kotlin.*` class and every wildcard of [type], with where it is. */
    private fun leaks(type: Type, at: String): List<String> = when (type) {
        is Class<*> -> when {
            type.isArray -> leaks(type.componentType, at)
            type.name.startsWith("kotlin.") -> listOf("$at: ${type.name}")
            else -> emptyList()
        }
        is ParameterizedType -> leaks(type.rawType, at) + type.actualTypeArguments.flatMap { leaks(it, at) }
        is WildcardType -> listOf("$at: wildcard $type")
        is GenericArrayType -> leaks(type.genericComponentType, at)
        is TypeVariable<*> -> type.bounds.flatMap { leaks(it, at) }
        else -> emptyList()
    }

    @Test
    fun `no public signature of the facade should name a Kotlin type or a wildcard`() {
        val leaks = facade.flatMap { type ->
            val methods = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.flatMap { method ->
                val at = "${type.simpleName}.${method.name}"
                leaks(method.genericReturnType, at) + method.genericParameterTypes.flatMap { leaks(it, at) }
            }
            val constructors = type.declaredConstructors.filter {
                Modifier.isPublic(it.modifiers) && !it.isSynthetic
            }.flatMap { constructor ->
                constructor.genericParameterTypes.flatMap { leaks(it, "${type.simpleName}.<init>") }
            }
            methods + constructors
        }
        leaks.assert().isEmpty()
        // The check sees a leak where there is one.
        leaks(kotlin.time.Duration::class.java, "probe").assert().hasSize(1)
        val probe = JavaFacadeSignaturesTest::class.java.declaredMethods.single { it.name == "wildcard" }
        leaks(probe.genericParameterTypes.single(), "probe").assert().hasSize(1)
    }

    /** The wildcard probe of the check above: Kotlin writes `List<? extends Number>` for this parameter. */
    @Suppress("unused", "UnusedParameter")
    private fun wildcard(numbers: List<Number>) = Unit
}
