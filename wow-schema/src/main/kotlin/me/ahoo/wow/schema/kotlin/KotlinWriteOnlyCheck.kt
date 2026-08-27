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

package me.ahoo.wow.schema.kotlin

import com.github.victools.jsonschema.generator.FieldScope
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.schema.Types.isKotlinElement
import java.util.function.Predicate
import kotlin.reflect.KVisibility
import kotlin.reflect.jvm.kotlinProperty

object KotlinWriteOnlyCheck : Predicate<FieldScope> {

    override fun test(fieldScope: FieldScope): Boolean {
        if (!fieldScope.declaringType.erasedType.isKotlinElement()) {
            return false
        }
        val property = fieldScope.rawMember.kotlinProperty ?: return false
        val schemaAnnotation = property.scanAnnotation<Schema>()
            ?: return property.getter.visibility == KVisibility.PRIVATE
        return schemaAnnotation.accessMode == Schema.AccessMode.WRITE_ONLY
    }
}
