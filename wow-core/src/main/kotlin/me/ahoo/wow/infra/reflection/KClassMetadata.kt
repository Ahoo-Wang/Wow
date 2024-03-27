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

object KClassMetadata {
    fun <T : Any> KClass<T>.visit(visitor: KClassVisitor<T>) {
        visitor.start()
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
