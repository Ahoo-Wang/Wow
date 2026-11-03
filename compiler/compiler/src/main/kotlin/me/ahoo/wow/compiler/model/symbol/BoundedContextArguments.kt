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

package me.ahoo.wow.compiler.model.symbol

import com.google.devtools.ksp.symbol.KSAnnotation
import me.ahoo.wow.api.annotation.BoundedContext

/**
 * What a `@BoundedContext` declares: the context's [name] and [description], read from the annotation's
 * arguments ([AnnotationArguments]) rather than through a proxy of the annotation class, which answers an argument KSP
 * has not evaluated as a null `String`.
 */
internal data class BoundedContextArguments(val name: String, val description: String) {
    companion object {
        private const val NAME = "name"
        private const val DESCRIPTION = "description"

        /** Whether [annotation] is a `@BoundedContext`. */
        fun isBoundedContext(annotation: KSAnnotation): Boolean = annotation.isOf(BoundedContext::class.java.name)

        /**
         * The context [annotation] declares; null while an argument it was given has no value yet. KSP leaves the
         * value of an argument null when it has not evaluated the constant expression the argument is, such as a
         * constant of another module, rather than failing. A description not given is the annotation's default,
         * empty.
         */
        fun of(annotation: KSAnnotation): BoundedContextArguments? =
            AnnotationArguments(BoundedContext::class, annotation).takeIf { it.unread.isEmpty() }?.let {
                BoundedContextArguments(it.string(NAME), it.string(DESCRIPTION))
            }
    }
}
