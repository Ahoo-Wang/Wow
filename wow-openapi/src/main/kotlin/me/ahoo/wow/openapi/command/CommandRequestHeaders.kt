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

package me.ahoo.wow.openapi.command

object CommandRequestHeaders {

    const val COMMAND_HEADERS_PREFIX = "Command-"
    const val WAIT_CONTEXT = "${COMMAND_HEADERS_PREFIX}Wait-Context"
    const val TENANT_ID = "${COMMAND_HEADERS_PREFIX}Tenant-Id"
    const val OWNER_ID = "${COMMAND_HEADERS_PREFIX}Owner-Id"
    const val AGGREGATE_ID = "${COMMAND_HEADERS_PREFIX}Aggregate-Id"
    const val AGGREGATE_VERSION = "${COMMAND_HEADERS_PREFIX}Aggregate-Version"
    const val WAIT_STAGE = "${COMMAND_HEADERS_PREFIX}Wait-Stage"
    const val WAIT_TIME_OUT = "${COMMAND_HEADERS_PREFIX}Wait-Timout"

    const val WAIT_PROCESSOR = "${COMMAND_HEADERS_PREFIX}Wait-Processor"
    const val REQUEST_ID = "${COMMAND_HEADERS_PREFIX}Request-Id"
    const val LOCAL_FIRST = "${COMMAND_HEADERS_PREFIX}Local-First"

    const val COMMAND_AGGREGATE_CONTEXT = "${COMMAND_HEADERS_PREFIX}Aggregate-Context"
    const val COMMAND_AGGREGATE_NAME = "${COMMAND_HEADERS_PREFIX}Aggregate-Name"
    const val COMMAND_TYPE = "${COMMAND_HEADERS_PREFIX}Type"

    const val COMMAND_HEADER_X_PREFIX = "${COMMAND_HEADERS_PREFIX}Header-"
    const val WOW_ERROR_CODE = "Wow-Error-Code"
}
