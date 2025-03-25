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

import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.RequestBody
import me.ahoo.wow.openapi.ApiResponseBuilder.Companion.DEFAULT_MEDIA_TYPE_NAME

class RequestBodyBuilder {
    private val requestBody = RequestBody().content(Content())
    fun description(description: String): RequestBodyBuilder {
        requestBody.description = description
        return this
    }

    fun content(name: String = DEFAULT_MEDIA_TYPE_NAME, mediaType: MediaType): RequestBodyBuilder {
        requestBody.content.addMediaType(name, mediaType)
        return this
    }

    fun content(name: String = DEFAULT_MEDIA_TYPE_NAME, schema: Schema<*>): RequestBodyBuilder {
        requestBody.content.addMediaType(name, MediaType().schema(schema))
        return this
    }

    fun build(): RequestBody {
        return requestBody
    }
}
