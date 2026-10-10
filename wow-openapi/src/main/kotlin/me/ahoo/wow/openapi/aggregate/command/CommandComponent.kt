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

import me.ahoo.wow.rest.CommandHeaders

object CommandComponent {
    object Header {
        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_HEADERS_PREFIX.",
            ReplaceWith("CommandHeaders.COMMAND_HEADERS_PREFIX", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_HEADERS_PREFIX = CommandHeaders.COMMAND_HEADERS_PREFIX

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.TENANT_ID.",
            ReplaceWith("CommandHeaders.TENANT_ID", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val TENANT_ID = CommandHeaders.TENANT_ID

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.OWNER_ID.",
            ReplaceWith("CommandHeaders.OWNER_ID", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val OWNER_ID = CommandHeaders.OWNER_ID

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.AGGREGATE_ID.",
            ReplaceWith("CommandHeaders.AGGREGATE_ID", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val AGGREGATE_ID = CommandHeaders.AGGREGATE_ID

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.AGGREGATE_VERSION.",
            ReplaceWith("CommandHeaders.AGGREGATE_VERSION", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val AGGREGATE_VERSION = CommandHeaders.AGGREGATE_VERSION

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_PREFIX.",
            ReplaceWith("CommandHeaders.WAIT_PREFIX", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_PREFIX = CommandHeaders.WAIT_PREFIX

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TIME_OUT.",
            ReplaceWith("CommandHeaders.WAIT_TIME_OUT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TIME_OUT = CommandHeaders.WAIT_TIME_OUT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.LEGACY_WAIT_TIME_OUT.",
            ReplaceWith("CommandHeaders.LEGACY_WAIT_TIME_OUT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val LEGACY_WAIT_TIME_OUT = CommandHeaders.LEGACY_WAIT_TIME_OUT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_STAGE.",
            ReplaceWith("CommandHeaders.WAIT_STAGE", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_STAGE = CommandHeaders.WAIT_STAGE

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_CONTEXT.",
            ReplaceWith("CommandHeaders.WAIT_CONTEXT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_CONTEXT = CommandHeaders.WAIT_CONTEXT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_PROCESSOR.",
            ReplaceWith("CommandHeaders.WAIT_PROCESSOR", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_PROCESSOR = CommandHeaders.WAIT_PROCESSOR

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_FUNCTION.",
            ReplaceWith("CommandHeaders.WAIT_FUNCTION", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_FUNCTION = CommandHeaders.WAIT_FUNCTION

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_PREFIX.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_PREFIX", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_PREFIX = CommandHeaders.WAIT_TAIL_PREFIX

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_STAGE.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_STAGE", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_STAGE = CommandHeaders.WAIT_TAIL_STAGE

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_CONTEXT.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_CONTEXT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_CONTEXT = CommandHeaders.WAIT_TAIL_CONTEXT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_PROCESSOR.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_PROCESSOR", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_PROCESSOR = CommandHeaders.WAIT_TAIL_PROCESSOR

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_FUNCTION.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_FUNCTION", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_FUNCTION = CommandHeaders.WAIT_TAIL_FUNCTION

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.REQUEST_ID.",
            ReplaceWith("CommandHeaders.REQUEST_ID", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val REQUEST_ID = CommandHeaders.REQUEST_ID

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.LOCAL_FIRST.",
            ReplaceWith("CommandHeaders.LOCAL_FIRST", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val LOCAL_FIRST = CommandHeaders.LOCAL_FIRST

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_AGGREGATE_CONTEXT.",
            ReplaceWith("CommandHeaders.COMMAND_AGGREGATE_CONTEXT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_AGGREGATE_CONTEXT = CommandHeaders.COMMAND_AGGREGATE_CONTEXT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_AGGREGATE_NAME.",
            ReplaceWith("CommandHeaders.COMMAND_AGGREGATE_NAME", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_AGGREGATE_NAME = CommandHeaders.COMMAND_AGGREGATE_NAME

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_TYPE.",
            ReplaceWith("CommandHeaders.COMMAND_TYPE", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_TYPE = CommandHeaders.COMMAND_TYPE

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_HEADER_X_PREFIX.",
            ReplaceWith("CommandHeaders.COMMAND_HEADER_X_PREFIX", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_HEADER_X_PREFIX = CommandHeaders.COMMAND_HEADER_X_PREFIX
    }
}
