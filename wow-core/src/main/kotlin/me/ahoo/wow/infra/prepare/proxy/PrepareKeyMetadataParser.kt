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

/**
 * Parser for extracting PrepareKey metadata from annotated interface classes.
 * This parser analyzes PrepareKey interfaces annotated with @PreparableKey to extract
 * the necessary metadata for proxy creation, including name, interface type, and value type.
 *
 * The parser caches parsed metadata for performance and supports both direct parsing
 * and extension functions for convenient access.
 *
 * @see PrepareKeyMetadata
 * @see PreparableKey
 * @see CacheableMetadataParser
 */
object PrepareKeyMetadataParser : CacheableMetadataParser() {
    /**
     * Parses the PrepareKey metadata from the given class.
     * Uses reflection to analyze the class hierarchy and annotations to extract
     * the metadata required for proxy creation.
     *
     * @param TYPE the class type to parse
     * @param M the metadata type (should be PrepareKeyMetadata)
     * @param type the Java class to parse
     * @return the parsed metadata
     */
    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val kType = type.kotlin
        val visitor = PrepareKeyMetadataVisitor(kType)
        kType.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }
}

/**
 * Visitor class for extracting PrepareKey metadata during class reflection.
 * This visitor analyzes the PrepareKey interface to determine the name (from annotation or class name),
 * the interface type, and the generic value type parameter.
 *
 * @param P the PrepareKey interface type
 * @property prepareKeyType the Kotlin class being visited
 */
class PrepareKeyMetadataVisitor<P : Any>(
    private val prepareKeyType: KClass<P>
) : ClassVisitor<P, PrepareKeyMetadata<P>> {
    /**
     * Extracts and constructs the PrepareKey metadata from the visited class.
     * Determines the name from @PreparableKey annotation or uses the simple class name.
     * Extracts the value type from the PrepareKey generic parameter.
     *
     * @return the constructed PrepareKey metadata
     */
    override fun toMetadata(): PrepareKeyMetadata<P> {
        val name =
            prepareKeyType.findAnnotation<PreparableKey>()?.name.orEmpty().ifBlank {
                prepareKeyType.simpleName!!
            }
        val superPrepareKeyType =
            prepareKeyType.supertypes.first {
                it.classifier == PrepareKey::class
            }
        val valueType = superPrepareKeyType.arguments[0].type!!.classifier as KClass<*>
        return PrepareKeyMetadata(name, prepareKeyType, valueType)
    }
}

/**
 * Extension function to parse PrepareKey metadata from a KClass.
 * Provides convenient access to metadata parsing for any PrepareKey interface class.
 *
 * @param P the PrepareKey interface type
 * @return the parsed metadata for this class
 */
fun <P : Any> KClass<out P>.prepareKeyMetadata(): PrepareKeyMetadata<P> = PrepareKeyMetadataParser.parse(this.java)

/**
 * Inline function to parse PrepareKey metadata using reified generics.
 * Allows for type-safe metadata parsing without explicitly specifying the class.
 *
 * @param P the PrepareKey interface type (inferred from usage)
 * @return the parsed metadata for the reified type
 */
inline fun <reified P : Any> prepareKeyMetadata(): PrepareKeyMetadata<P> = P::class.prepareKeyMetadata()
