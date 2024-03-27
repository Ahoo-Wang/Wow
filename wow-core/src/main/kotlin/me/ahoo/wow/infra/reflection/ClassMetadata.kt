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

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Class Metadata .
 *
 * @author ahoo wang
 */
@Deprecated("Use KClassMetadata instead.")
object ClassMetadata {
    @JvmStatic
    fun <T> visitField(type: Class<T>, fieldConsumer: Consumer<Field>) {
        visit(
            type,
            object : ClassVisitor {
                override fun visitField(field: Field) {
                    fieldConsumer.accept(field)
                }
            },
        )
    }

    @JvmStatic
    fun <T> visitMethod(type: Class<T>, methodConsumer: Consumer<Method>) {
        visit(
            type,
            object : ClassVisitor {
                override fun visitMethod(method: Method) {
                    methodConsumer.accept(method)
                }
            },
        )
    }

    @JvmStatic
    fun <T> visit(type: Class<T>, visitor: ClassVisitor) {
        visitor.start()
        visit(visitor, type)
        for (interfaceType in type.interfaces) {
            visit(visitor, interfaceType)
        }
        var currentDeclaringClass = type.superclass
        while (Any::class.java != currentDeclaringClass && currentDeclaringClass != null) {
            visit(visitor, currentDeclaringClass)
            currentDeclaringClass = currentDeclaringClass.superclass
        }
        visitor.end()
    }

    private fun visit(visitor: ClassVisitor, currentDeclaringClass: Class<*>) {
        visitor.visitClass(currentDeclaringClass)
        for (declaredField in currentDeclaringClass.declaredFields) {
            visitor.visitField(declaredField!!)
        }
        for (declaredConstructor in currentDeclaringClass.declaredConstructors) {
            visitor.visitConstructor(declaredConstructor!!)
        }
        for (declaredMethod in currentDeclaringClass.declaredMethods) {
            visitor.visitMethod(declaredMethod!!)
        }
    }
}
