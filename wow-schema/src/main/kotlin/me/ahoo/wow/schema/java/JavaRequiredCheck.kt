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

package me.ahoo.wow.schema.java

import com.github.victools.jsonschema.generator.FieldScope
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.schema.Types.isKotlinElement
import java.util.function.Predicate

/**
 * Marks the components of a Java record as required.
 *
 * A record's canonical constructor takes every component, so none of them can be omitted.
 * Plain Java beans carry no such contract and are left to the Jackson and Swagger annotations.
 */
object JavaRequiredCheck : Predicate<FieldScope> {

    override fun test(fieldScope: FieldScope): Boolean {
        val declaringType = fieldScope.declaringType.erasedType
        if (declaringType.isKotlinElement() || !declaringType.isRecord) {
            return false
        }
        val schemaAnnotation = fieldScope.getAnnotationConsideringFieldAndGetter(Schema::class.java)
        if (schemaAnnotation != null && schemaAnnotation.requiredMode != Schema.RequiredMode.AUTO) {
            return schemaAnnotation.requiredMode == Schema.RequiredMode.REQUIRED
        }
        return declaringType.recordComponents.any { it.name == fieldScope.name }
    }
}
