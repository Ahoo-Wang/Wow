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

package me.ahoo.wow.rest

/**
 * The request headers of Wow's command routes: the command's target aggregate, and how long and for what the request
 * waits. Part of the REST contract, frozen within a major version.
 */
object CommandHeaders {
    const val COMMAND_HEADERS_PREFIX = "Command-"

    const val TENANT_ID = "${COMMAND_HEADERS_PREFIX}Tenant-Id"
    const val OWNER_ID = "${COMMAND_HEADERS_PREFIX}Owner-Id"
    const val AGGREGATE_ID = "${COMMAND_HEADERS_PREFIX}Aggregate-Id"
    const val AGGREGATE_VERSION = "${COMMAND_HEADERS_PREFIX}Aggregate-Version"

    const val WAIT_PREFIX = "${COMMAND_HEADERS_PREFIX}Wait-"
    const val WAIT_TIME_OUT = "${WAIT_PREFIX}Timeout"

    /** The misspelt name of [WAIT_TIME_OUT], which the server still reads when a request does not send that one. */
    const val LEGACY_WAIT_TIME_OUT = "${WAIT_PREFIX}Timout"

    //region Wait Stage
    const val WAIT_STAGE = "${WAIT_PREFIX}Stage"
    const val WAIT_CONTEXT = "${WAIT_PREFIX}Context"
    const val WAIT_PROCESSOR = "${WAIT_PREFIX}Processor"
    const val WAIT_FUNCTION = "${WAIT_PREFIX}Function"

    //endregion
    //region Wait Chain Tail
    const val WAIT_TAIL_PREFIX = "${WAIT_PREFIX}Tail-"
    const val WAIT_TAIL_STAGE = "${WAIT_TAIL_PREFIX}Stage"
    const val WAIT_TAIL_CONTEXT = "${WAIT_TAIL_PREFIX}Context"
    const val WAIT_TAIL_PROCESSOR = "${WAIT_TAIL_PREFIX}Processor"
    const val WAIT_TAIL_FUNCTION = "${WAIT_TAIL_PREFIX}Function"

    //endregion
    const val REQUEST_ID = "${COMMAND_HEADERS_PREFIX}Request-Id"
    const val LOCAL_FIRST = "${COMMAND_HEADERS_PREFIX}Local-First"

    const val COMMAND_AGGREGATE_CONTEXT = "${COMMAND_HEADERS_PREFIX}Aggregate-Context"
    const val COMMAND_AGGREGATE_NAME = "${COMMAND_HEADERS_PREFIX}Aggregate-Name"
    const val COMMAND_TYPE = "${COMMAND_HEADERS_PREFIX}Type"

    /** The prefix of a request header the server copies, without the prefix, into the command message's header. */
    const val COMMAND_HEADER_X_PREFIX = "${COMMAND_HEADERS_PREFIX}Header-"
}
