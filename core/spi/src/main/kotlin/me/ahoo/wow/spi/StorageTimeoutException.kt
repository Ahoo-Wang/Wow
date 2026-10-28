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

package me.ahoo.wow.spi

import java.time.Duration

/**
 * A store stopped a statement at the time limit its caller gave it ([timeout]): PostgreSQL's `statement_timeout`,
 * MongoDB's `maxTimeMS`, ClickHouse's `max_execution_time`. The store is up; the statement asked too much of it, so
 * running it again as it is fails the same way.
 *
 * A backend throws it, with the driver's exception as [cause], from a call that bounds its statement. The query core
 * answers it as `wow.query.timeout`; a subscription handler retries it within its budget.
 */
class StorageTimeoutException(val timeout: Duration, cause: Throwable? = null) :
    RuntimeException("The store stopped the statement at its timeout of ${timeout.toMillis()} ms.", cause)
