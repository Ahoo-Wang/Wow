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

package me.ahoo.wow.api.serialization

import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JavaType
import tools.jackson.databind.deser.DeserializationProblemHandler
import tools.jackson.databind.jsontype.TypeIdResolver
import kotlin.reflect.KClass

/**
 * Declares the default implementation of a polymorphic base type when its JSON type id is missing.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class MissingTypeImpl(val impl: KClass<*>)

/**
 * Resolves missing Jackson polymorphic type ids using [MissingTypeImpl].
 */
class MissingTypeImplProblemHandler : DeserializationProblemHandler() {
    override fun handleMissingTypeId(
        ctxt: DeserializationContext,
        baseType: JavaType,
        idResolver: TypeIdResolver,
        failureMsg: String,
    ): JavaType? = baseType.rawClass.getAnnotation(MissingTypeImpl::class.java)
        ?.let { ctxt.constructSpecializedType(baseType, it.impl.java) }
}
