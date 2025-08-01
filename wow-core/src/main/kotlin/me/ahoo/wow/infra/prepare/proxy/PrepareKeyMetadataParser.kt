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

package me.ahoo.wow.infra.prepare.proxy

import me.ahoo.wow.api.annotation.PreparableKey
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

object PrepareKeyMetadataParser : CacheableMetadataParser() {

    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val kType = type.kotlin
        val visitor = PrepareKeyMetadataVisitor(kType)
        kType.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }
}

class PrepareKeyMetadataVisitor<P : Any>(private val prepareKeyType: KClass<P>) :
    ClassVisitor<P, PrepareKeyMetadata<P>> {
    override fun toMetadata(): PrepareKeyMetadata<P> {
        val name = prepareKeyType.findAnnotation<PreparableKey>()?.name.orEmpty().ifBlank {
            prepareKeyType.simpleName!!
        }
        val superPrepareKeyType = prepareKeyType.supertypes.first {
            it.classifier == PrepareKey::class
        }
        val valueType = superPrepareKeyType.arguments[0].type!!.classifier as KClass<*>
        return PrepareKeyMetadata(name, prepareKeyType, valueType)
    }
}

fun <P : Any> KClass<out P>.prepareKeyMetadata(): PrepareKeyMetadata<P> {
    return PrepareKeyMetadataParser.parse(this.java)
}

inline fun <reified P : Any> prepareKeyMetadata(): PrepareKeyMetadata<P> {
    return P::class.prepareKeyMetadata()
}
