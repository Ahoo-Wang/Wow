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

package me.ahoo.wow.openapi.contract

import io.swagger.v3.oas.models.headers.Header
import me.ahoo.wow.openapi.component.CommonComponents

/**
 * The components the built-in routes share, for custom route contributors. Each value is the instance the built-in
 * routes use, so a custom contract that references it renders a `$ref` to the same component (`wow.BadRequest`,
 * `wow.Wow-Error-Code`, …) and never trips the duplicate component key check, which a copy of the definition would.
 */
object WowComponents {
    /** The `wow.Wow-Error-Code` header component, to reference with `context.ref` in a custom response builder. */
    val errorCodeHeaderComponent: HttpComponent<Header> = CommonComponents.ERROR_CODE_HEADER

    /** The `Wow-Error-Code` response header, referencing [errorCodeHeaderComponent]. */
    val errorCodeHeader: HttpHeader = CommonComponents.errorCodeHeader

    /** `400` with the error info body and the error code header (`wow.BadRequest`). */
    val badRequestResponse: HttpResponse = CommonComponents.badRequestResponse

    /** `404` with the error info body and the error code header (`wow.NotFound`). */
    val notFoundResponse: HttpResponse = CommonComponents.notFoundResponse

    /** `408` with the error info body and the error code header (`wow.RequestTimeout`). */
    val requestTimeoutResponse: HttpResponse = CommonComponents.requestTimeoutResponse

    /** `429` with the error info body and the error code header (`wow.TooManyRequests`). */
    val tooManyRequestsResponse: HttpResponse = CommonComponents.tooManyRequestsResponse

    /** `415` with the error info body and the error code header (`wow.UnsupportedMediaType`). */
    val unsupportedMediaTypeResponse: HttpResponse = CommonComponents.unsupportedMediaTypeResponse

    /** The optional `Wow-Space-Id` request header (`wow.Wow-Space-Id`). */
    val spaceIdHeaderParameter: HttpParameter = CommonComponents.spaceIdHeaderParameter

    /** The required `{id}` path parameter: the aggregate id (`wow.id`). */
    val idPathParameter: HttpParameter = CommonComponents.idPathParameter

    /** The required `{tenantId}` path parameter (`wow.tenantId`). */
    val tenantIdPathParameter: HttpParameter = CommonComponents.tenantIdPathParameter

    /** The required `{ownerId}` path parameter (`wow.ownerId`). */
    val ownerIdPathParameter: HttpParameter = CommonComponents.ownerIdPathParameter

    /** The required `{version}` path parameter: the aggregate version (`wow.version`). */
    val versionPathParameter: HttpParameter = CommonComponents.versionPathParameter
}
