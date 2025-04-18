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

package me.ahoo.wow.exception

import me.ahoo.wow.api.exception.ErrorInfo

object ErrorCodes {
    const val SUCCEEDED = ErrorInfo.SUCCEEDED
    const val SUCCEEDED_MESSAGE = ErrorInfo.SUCCEEDED_MESSAGE

    const val NOT_FOUND = "NotFound"
    const val NOT_FOUND_MESSAGE = "Not found resource!"

    /**
     * Undefined client exception
     */
    const val BAD_REQUEST = "BadRequest"

    /**
     * @see IllegalArgumentException
     */
    const val ILLEGAL_ARGUMENT = "IllegalArgument"

    /**
     * @see IllegalStateException
     */
    const val ILLEGAL_STATE = "IllegalState"

    //region Command
    /**
     * @see java.util.concurrent.TimeoutException
     */
    const val REQUEST_TIMEOUT = "RequestTimeout"
    const val TOO_MANY_REQUESTS = "TooManyRequests"
    const val DUPLICATE_REQUEST_ID = "DuplicateRequestId"
    const val COMMAND_VALIDATION = "CommandValidation"
    const val REWRITE_NO_COMMAND = "RewriteNoCommand"

    //endregion
    //region EventSourcing
    const val EVENT_VERSION_CONFLICT = "EventVersionConflict"
    const val DUPLICATE_AGGREGATE_ID = "DuplicateAggregateId"

    //endregion
    //region Aggregate
    const val COMMAND_EXPECT_VERSION_CONFLICT = "CommandExpectVersionConflict"
    const val SOURCING_VERSION_CONFLICT = "SourcingVersionConflict"
    const val ILLEGAL_ACCESS_DELETED_AGGREGATE = "IllegalAccessDeletedAggregate"
    const val ILLEGAL_ACCESS_OWNER_AGGREGATE = "IllegalAccessOwnerAggregate"
    //endregion

    const val INTERNAL_SERVER_ERROR = "InternalServerError"
}
