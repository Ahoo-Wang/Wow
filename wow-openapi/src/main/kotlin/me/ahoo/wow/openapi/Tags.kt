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

package me.ahoo.wow.openapi

import io.swagger.v3.core.util.AnnotationsUtils
import io.swagger.v3.oas.annotations.tags.Tags
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import kotlin.jvm.optionals.getOrDefault
import io.swagger.v3.oas.annotations.tags.Tag as AnnotationTag

object Tags {
    fun Class<*>.toTags(): Set<Tag> {
        val annotationTags = mutableSetOf<AnnotationTag>()

        kotlin.scanAnnotation<AnnotationTag>()?.let {
            annotationTags.add(it)
        }
        kotlin.scanAnnotation<Tags>()?.let { values ->
            annotationTags.addAll(values.value)
        }
        return AnnotationsUtils.getTags(annotationTags.toTypedArray(), false).getOrDefault(emptySet())
    }
}
