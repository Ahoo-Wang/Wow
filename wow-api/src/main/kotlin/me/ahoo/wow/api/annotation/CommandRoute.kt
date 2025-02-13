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

package me.ahoo.wow.api.annotation

import java.lang.annotation.Inherited

const val DEFAULT_COMMAND_ACTION = "__{command_name}__"

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class CommandRoute(
    /**
     * action name or sub resource name
     */
    val path: String = DEFAULT_COMMAND_ACTION,
    val enabled: Boolean = true,
    val method: Method = Method.DEFAULT,
    val prefix: String = "",
    val appendIdPath: AppendPath = AppendPath.DEFAULT,
    val appendTenantPath: AppendPath = AppendPath.DEFAULT,
    @Deprecated("Will be removed in version 5.3")
    val ignoreAggregateNamePrefix: Boolean = false,
    val summary: String = "",
    val description: String = "",
) {

    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.ANNOTATION_CLASS
    )
    @Repeatable
    @Inherited
    annotation class PathVariable(
        val name: String = "",
        val nestedPath: Array<String> = [],
        val required: Boolean = true
    )

    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.ANNOTATION_CLASS
    )
    @Repeatable
    @Inherited
    annotation class HeaderVariable(
        val name: String = "",
        val nestedPath: Array<String> = [],
        val required: Boolean = true
    )

    enum class Method {
        POST,
        PUT,
        DELETE,
        PATCH,
        DEFAULT
    }

    enum class AppendPath {
        ALWAYS,
        NEVER,
        DEFAULT
    }
}
