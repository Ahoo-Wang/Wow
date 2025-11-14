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
 * Configures routing and HTTP endpoint generation for command classes.
 *
 * This annotation defines how commands are exposed as REST API endpoints, including
 * the HTTP method, URL structure, and path parameters. It's essential for generating
 * OpenAPI documentation and enabling HTTP-based command dispatching.
 *
 *
 * Example usage:
 * ```kotlin
 * @CommandRoute(
 *     action = "create",
 *     method = CommandRoute.Method.POST,
 *     appendIdPath = CommandRoute.AppendPath.NEVER,  // No ID in path for creation
 *     appendTenantPath = CommandRoute.AppendPath.ALWAYS
 * )
 * data class CreateOrderCommand(
 *     @TenantId
 *     val tenantId: String,
 *     val items: List<OrderItem>
 * )
 * ```
 * // Generates: POST /orders/tenant/{tenantId}/create
 *
 * @param action The action name or sub-resource identifier. Used in URL path generation.
 *              Defaults to a dynamic action based on the command class name.
 * @param enabled Whether this command route should be active. When false, no endpoint
 *               will be generated. Defaults to true.
 * @param method The HTTP method for the endpoint. Determines the REST operation type.
 * @param prefix URL prefix to prepend to the generated path. Useful for API versioning.
 * @param appendIdPath Whether to include the aggregate ID in the URL path.
 * @param appendTenantPath Whether to include the tenant ID in the URL path for multi-tenant scenarios.
 * @param appendOwnerPath Whether to include the owner ID in the URL path for ownership-based routing.
 *
 * @see Method for available HTTP methods
 * @see AppendPath for path appending options
 * @see Summary for current API documentation approach

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
    val appendOwnerPath: AppendPath = AppendPath.DEFAULT
) {
    /**
     * Marks a command field as a path variable in the generated URL.
     *
     * Path variables are extracted from the URL path and injected into the command.
     * This enables REST-style URLs with dynamic segments.
     *
     * @param name The name of the path variable. If empty, uses the field name.
     * @param nestedPath Path to nested properties for complex objects (e.g., ["user", "id"]).
     * @param required Whether this path variable is mandatory. Affects URL generation.
     */
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.ANNOTATION_CLASS,
    )
    @Repeatable
    @Inherited
    annotation class PathVariable(
        val name: String = "",
        val nestedPath: Array<String> = [],
        val required: Boolean = true
    )

    /**
     * Marks a command field as a header variable in HTTP requests.
     *
     * Header variables are extracted from HTTP headers and injected into the command.
     * This enables passing context information like authentication tokens or request metadata.
     *
     * @param name The name of the HTTP header. If empty, uses the field name.
     * @param nestedPath Path to nested properties for complex objects.
     * @param required Whether this header is mandatory for the request.
     */
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.ANNOTATION_CLASS,
    )
    @Repeatable
    @Inherited
    annotation class HeaderVariable(
        val name: String = "",
        val nestedPath: Array<String> = [],
        val required: Boolean = true
    )

    /**
     * HTTP methods for command routing.
     */
    enum class Method {
        /** Create new resources */
        POST,

        /** Update existing resources completely */
        PUT,

        /** Delete resources */
        DELETE,

        /** Partial updates to resources */
        PATCH,

        /** Use framework default method based on command type */
        DEFAULT
    }

    /**
     * Controls whether IDs are appended to URL paths.
     */
    enum class AppendPath {
        /** Always append the ID to the path */
        ALWAYS,

        /** Never append the ID to the path */
        NEVER,

        /** Use framework default behavior based on command type */
        DEFAULT
    }
}
