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

package me.ahoo.wow.runtime

import me.ahoo.wow.kernel.KernelRejections
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.wait.ResultCodes

/**
 * Every error code the framework itself answers with (design 05 §3; decisions D60, D103), in one place for the edge to
 * map to its statuses: the admission codes defined here, the kernel's rejections ([KernelRejections]) and the
 * runtime's results ([ResultCodes]), and the job aggregate's rejection ([ScheduleJobModel.JOB_INVALID]). A domain's own codes are not among them.
 *
 * They are codes of the Manifest's error table ([FrameworkErrors], the one source of truth, review R2.5-4): the ones
 * defined here and in [ResultCodes] are its constants, and the kernel's repeat its strings; a test of `wow-http`
 * fails when a code here is missing from the table.
 */
object FrameworkCodes {
    /** The request carries no credentials the edge accepts (HTTP 401). The edge answers it; admission never does. */
    const val UNAUTHENTICATED = FrameworkErrors.AUTH_UNAUTHENTICATED

    /**
     * The caller may not do this (decision D95): an authorizer denied the action, or the request asked for another
     * scope (`Wow-Scope`) without the permission to act across scopes.
     */
    const val AUTH_FORBIDDEN = FrameworkErrors.AUTH_FORBIDDEN

    /**
     * The request cannot be read as the command it names: its body does not decode as the command type, or it gives
     * an identity fact twice or not at all (decision D92).
     */
    const val MALFORMED = FrameworkErrors.COMMAND_MALFORMED

    /** The command's body breaks a validation rule; the error lists the violations (decision D106). */
    const val INVALID = FrameworkErrors.COMMAND_INVALID

    const val UNHANDLED = KernelRejections.UNHANDLED
    const val AGGREGATE_FORBIDDEN = KernelRejections.FORBIDDEN
    const val NOT_FOUND = KernelRejections.NOT_FOUND
    const val EXISTS = KernelRejections.EXISTS
    const val VERSION_MISMATCH = KernelRejections.VERSION_MISMATCH
    const val REQUEST_ID_REUSED = KernelRejections.REQUEST_ID_REUSED
    const val DELETED = KernelRejections.DELETED
    const val CONTENDED = ResultCodes.CONTENDED
    const val UNKNOWN = ResultCodes.UNKNOWN
    const val FAILED = ResultCodes.FAILED
    const val BUSY = ResultCodes.BUSY
    const val NODE_CLOSING = ResultCodes.NODE_CLOSING
    const val STORAGE_UNAVAILABLE = ResultCodes.STORAGE_UNAVAILABLE
    const val SCHEDULE_NOT_DUE = ResultCodes.SCHEDULE_NOT_DUE
    const val SCHEDULE_JOB_INVALID = ScheduleJobModel.JOB_INVALID
    const val WAIT_INVALID = ResultCodes.WAIT_INVALID
    const val WAIT_TIMEOUT = ResultCodes.WAIT_TIMEOUT
    const val SUBSCRIPTION_PARKED = ResultCodes.SUBSCRIPTION_PARKED

    /** All of them. */
    val ALL: Set<String> = setOf(
        UNAUTHENTICATED,
        AUTH_FORBIDDEN,
        MALFORMED,
        INVALID,
        UNHANDLED,
        AGGREGATE_FORBIDDEN,
        NOT_FOUND,
        EXISTS,
        VERSION_MISMATCH,
        REQUEST_ID_REUSED,
        DELETED,
        CONTENDED,
        UNKNOWN,
        FAILED,
        BUSY,
        NODE_CLOSING,
        STORAGE_UNAVAILABLE,
        SCHEDULE_NOT_DUE,
        SCHEDULE_JOB_INVALID,
        WAIT_INVALID,
        WAIT_TIMEOUT,
        SUBSCRIPTION_PARKED,
    )
}
