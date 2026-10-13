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

package me.ahoo.wow.manifest.tools

import java.lang.reflect.AnnotatedElement

/**
 * The query field annotations of `wow-query-api` (`me.ahoo.wow.api.query.annotation`), read by their class names.
 *
 * `manifest-tools` may not depend on `:query-api` (the V10 module rules allow it `:manifest` and `:kernel` only), so
 * it reads the annotations the compiled domain classes carry through reflection: the annotation types come from the
 * domain's own class path, the worker's, and each value is read by the name of its annotation member.
 */
internal object QueryAnnotations {
    private const val PACKAGE = "me.ahoo.wow.api.query.annotation"
    const val SENSITIVE = "$PACKAGE.Sensitive"
    const val MASK_STRATEGY = "$PACKAGE.MaskStrategy"
    const val QUERY_ALIAS = "$PACKAGE.QueryAlias"
    const val QUERY_TEMPORAL = "$PACKAGE.QueryTemporal"
    const val QUERY_DECIMAL = "$PACKAGE.QueryDecimal"
    const val QUERY_MONEY = "$PACKAGE.QueryMoney"
    const val QUERY_DURATION = "$PACKAGE.QueryDuration"
    const val QUERY_REFERENCE = "$PACKAGE.QueryReference"
    const val QUERY_INDEX = "$PACKAGE.QueryIndex"

    /** The annotations of type [type] among [annotations], also those a repeatable annotation's container holds. */
    fun named(annotations: Iterable<Annotation>, type: String): List<AnnotationValues> =
        annotations.flatMap { annotation ->
            if (annotation.annotationClass.java.name == type) {
                listOf(annotation)
            } else {
                containerValue(annotation, type).orEmpty()
            }
        }.map(::AnnotationValues)

    /** The annotations of type [type] that [element] carries, also those a repeatable annotation's container holds. */
    fun named(element: AnnotatedElement, type: String): List<AnnotationValues> =
        named(element.annotations.asList(), type)

    /** The `value` of a repeatable annotation's container of [type], or `null` when [annotation] is not one. */
    private fun containerValue(annotation: Annotation, type: String): List<Annotation>? {
        val value = annotation.annotationClass.java.declaredMethods.singleOrNull { it.name == "value" } ?: return null
        if (value.returnType.componentType?.name != type) return null
        return (value.invoke(annotation) as Array<*>).map { it as Annotation }
    }
}

/** The member values of one annotation, read by member name. */
internal class AnnotationValues(val annotation: Annotation) {
    val simpleName: String
        get() = annotation.annotationClass.java.simpleName

    operator fun get(member: String): Any? = annotation.annotationClass.java.getMethod(member).invoke(annotation)

    fun string(member: String): String = get(member) as String

    fun int(member: String): Int = get(member) as Int

    fun strings(member: String): List<String> = (get(member) as Array<*>).map { it as String }

    fun enum(member: String): Enum<*> = get(member) as Enum<*>

    fun type(member: String): Class<*> = get(member) as Class<*>

    fun annotation(member: String): AnnotationValues = AnnotationValues(get(member) as Annotation)
}
