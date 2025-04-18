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

/**
 * Marks a class or annotation as a command route, defining the action, method, and other properties for handling commands.
 *
 * @param action The name of the action or sub-resource. Defaults to [DEFAULT_COMMAND_ACTION].
 * @param enabled Whether the command route is enabled. Defaults to `true`.
 * @param method The HTTP method associated with the command. Defaults to [Method.DEFAULT].
 * @param prefix A prefix to be added to the command path. Defaults to an empty string.
 * @param appendIdPath Determines if the ID path should be appended. Defaults to [AppendPath.DEFAULT].
 * @param appendTenantPath Determines if the tenant path should be appended. Defaults to [AppendPath.DEFAULT].
 * @param appendOwnerPath Determines if the owner path should be appended. Defaults to [AppendPath.DEFAULT].
 * @param summary A deprecated field for providing a summary. Use @Summary instead. Defaults to an empty string.
 * @param description A deprecated field for providing a description. Use @Description instead. Defaults to an empty string.
 *
 * Example usage:
 * ```kotlin
 * @CommandRoute(
 *     action = "create",
 *     method = CommandRoute.Method.POST,
 *     prefix = "/api/v1",
 *     appendIdPath = CommandRoute.AppendPath.ALWAYS,
 *     enabled = true
 * )
 * class CreateResourceCommand {
 *     // Command implementation
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class CommandRoute(
    /**
     * action name or sub resource name
     */
    val action: String = DEFAULT_COMMAND_ACTION,
    val enabled: Boolean = true,
    val method: Method = Method.DEFAULT,
    val prefix: String = "",
    val appendIdPath: AppendPath = AppendPath.DEFAULT,
    val appendTenantPath: AppendPath = AppendPath.DEFAULT,
    val appendOwnerPath: AppendPath = AppendPath.DEFAULT,
    @Deprecated(
        message = "use @Summary instead.",
        replaceWith = ReplaceWith("@Summary(summary)")
    )
    val summary: String = "",
    @Deprecated(
        message = "use @Description instead.",
        replaceWith = ReplaceWith("@Description(description)")
    )
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
