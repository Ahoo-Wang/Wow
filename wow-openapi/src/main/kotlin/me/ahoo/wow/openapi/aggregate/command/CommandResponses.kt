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

package me.ahoo.wow.openapi.aggregate.command

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.MediaType
import me.ahoo.wow.api.Wow
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef
import me.ahoo.wow.openapi.toJsonContent

object CommandResponses {

    val COMMAND_RESULT_SCHEMA = CommandResult::class.java.toSchemaRef()
    val COMMAND_RESULT_CONTENT = COMMAND_RESULT_SCHEMA.ref.toJsonContent {
        it.addMediaType(
            Https.MediaType.TEXT_EVENT_STREAM,
            MediaType().schema(ArraySchema().items(COMMAND_RESULT_SCHEMA.ref))
        )
    }
    val COMMAND_RESULT_RESPONSE = ResponseRef(
        name = "${Wow.WOW_PREFIX}CommandResult",
        component = COMMAND_RESULT_CONTENT.toResponse(),
        code = Https.Code.OK
    )
    val BAD_REQUEST_RESPONSE = ResponseRef(
        name = "${Wow.WOW_PREFIX}CommandBadRequest",
        component = COMMAND_RESULT_CONTENT.toResponse(description = "Bad Request"),
        code = Https.Code.BAD_REQUEST
    )
    val NOT_FOUND_RESPONSE = ResponseRef(
        name = "${Wow.WOW_PREFIX}CommandNotFound",
        component = COMMAND_RESULT_CONTENT.toResponse("Not Found"),
        code = Https.Code.NOT_FOUND
    )
    val REQUEST_TIMEOUT_RESPONSE = ResponseRef(
        name = "${Wow.WOW_PREFIX}CommandRequestTimeout",
        component = COMMAND_RESULT_CONTENT.toResponse("Request Timeout"),
        code = Https.Code.REQUEST_TIMEOUT
    )
    val TOO_MANY_REQUESTS_RESPONSE = ResponseRef(
        name = "${Wow.WOW_PREFIX}CommandTooManyRequests",
        component = COMMAND_RESULT_CONTENT.toResponse("Too Many Requests"),
        code = Https.Code.TOO_MANY_REQUESTS
    )
    val VERSION_CONFLICT_RESPONSE = ResponseRef(
        name = "${Wow.WOW_PREFIX}VersionConflict",
        component = COMMAND_RESULT_CONTENT.toResponse(description = "Version Conflict"),
        code = Https.Code.CONFLICT
    )
    val ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE = ResponseRef(
        name = "${Wow.WOW_PREFIX}IllegalAccessDeletedAggregate",
        component = COMMAND_RESULT_CONTENT.toResponse(description = "Illegal Access Deleted Aggregate"),
        code = Https.Code.GONE
    )
}
