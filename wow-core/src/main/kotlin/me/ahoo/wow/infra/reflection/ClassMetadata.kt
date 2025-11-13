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

import kotlin.reflect.KClass
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType

/**
 * Utility object for visiting and analyzing Kotlin class metadata.
 * Provides a visitor pattern implementation to traverse class elements including
 * types, constructors, properties, and functions in a structured way.
 *
 * This is useful for frameworks that need to introspect class structures
 * for code generation, validation, or runtime analysis.
 */
object ClassMetadata {
    /**
     * Visits all metadata elements of a Kotlin class using the provided visitor.
     * The visit follows a structured pattern: start, visit types, constructors,
     * properties, functions, and end. This ensures consistent traversal order.
     *
     * @param T the type of the class being visited
     * @param visitor the visitor that will process each class element
     *
     * @sample
     * ```
     * class MyVisitor : ClassVisitor<MyClass, Unit> {
     *     override fun start() { println("Starting visit") }
     *     override fun visitType(type: KType) { println("Type: $type") }
     *     override fun visitConstructor(constructor: KFunction<MyClass>) { println("Constructor: $constructor") }
     *     override fun visitProperty(property: KProperty1<MyClass, *>) { println("Property: $property") }
     *     override fun visitFunction(function: KFunction<*>) { println("Function: $function") }
     *     override fun end() { println("Visit complete") }
     * }
     *
     * MyClass::class.visit(MyVisitor())
     * ```
     */
    fun <T : Any> KClass<T>.visit(visitor: ClassVisitor<T, *>) {
        visitor.start()
        visitor.visitType(this.starProjectedType)
        supertypes.forEach {
            visitor.visitType(it)
        }
        /**
         * Can't compute ClassId for primitive type: long
         *
         * This is a known issue with Kotlin reflection. The issue is that Kotlin reflection doesn't support Java Record types.
         *
         * https://youtrack.jetbrains.com/issue/KT-58649
         */
        try {
            constructors.forEach {
                visitor.visitConstructor(it)
            }
        } catch (ignore: IllegalArgumentException) {
            // ignore
        }

        memberProperties.forEach {
            visitor.visitProperty(it)
        }
        memberFunctions.forEach {
            visitor.visitFunction(it)
        }
        visitor.end()
    }
}
