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
import com.google.devtools.ksp.symbol.KSValueArgument
import me.ahoo.wow.api.annotation.BoundedContext

/**
 * What a `@BoundedContext` declares: the context's [name] and [description], read from the annotation's
 * arguments rather than through a proxy of the annotation class, which answers an argument KSP has not evaluated as a
 * null `String`.
 */
internal data class BoundedContextArguments(val name: String, val description: String) {
    companion object {
        private const val NAME = "name"
        private const val DESCRIPTION = "description"

        /** Whether [annotation] is a `@BoundedContext`. */
        fun isBoundedContext(annotation: KSAnnotation): Boolean =
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() ==
                BoundedContext::class.java.name

        /**
         * The context [annotation] declares; null while an argument it was given has no value yet. KSP leaves the
         * value of an argument null when it has not evaluated the constant expression the argument is, such as a
         * constant of another module, rather than failing. A description not given at all is the annotation's
         * default, empty.
         */
        fun of(annotation: KSAnnotation): BoundedContextArguments? {
            val name = annotation.argumentNamed(NAME)?.value as? String ?: return null
            val description = annotation.argumentNamed(DESCRIPTION)
            if (description == null) {
                return BoundedContextArguments(name, "")
            }
            return (description.value as? String)?.let { BoundedContextArguments(name, it) }
        }

        /** The argument [name] of this annotation, as written, else by default; null when it has none of that name. */
        private fun KSAnnotation.argumentNamed(name: String): KSValueArgument? =
            arguments.firstOrNull { it.name?.asString() == name }
                ?: defaultArguments.firstOrNull { it.name?.asString() == name }
    }
}
