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

package me.ahoo.wow.spi.log

/**
 * How an append ended. Every expected result is a value; only a defect is an exception.
 */
sealed interface AppendOutcome {
    /** The stream is committed at [position]. */
    data class Committed(val position: LogPosition) : AppendOutcome

    /** A stream with the same tenant and request id is already committed: [existing]. */
    data class AlreadyCommitted(val existing: EventStreamRecord) : AppendOutcome

    /** Another stream already holds the version; the aggregate is at [currentVersion]. */
    data class Conflict(val currentVersion: Int) : AppendOutcome

    /**
     * Whether the append committed is unknown, for example after a timeout. The version slot decides it: read the
     * stream at the appended version before deciding again.
     */
    data class Unknown(val cause: Throwable) : AppendOutcome
}
