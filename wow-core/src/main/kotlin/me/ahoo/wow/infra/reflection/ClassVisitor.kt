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

import me.ahoo.wow.metadata.Metadata
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.KType

/**
 * KClass Visitor .
 * @author ahoo wang
 */

interface ClassVisitor<T, M : Metadata> : VisitorLifeCycle {

    fun visitType(type: KType) = Unit

    fun visitProperty(property: KProperty1<T, *>) = Unit

    fun visitConstructor(constructor: KFunction<*>) = Unit

    fun visitFunction(function: KFunction<*>) = Unit

    fun toMetadata(): M
}
