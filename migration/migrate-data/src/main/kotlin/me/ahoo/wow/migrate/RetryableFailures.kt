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

package me.ahoo.wow.migrate

import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import me.ahoo.wow.spi.StorageTimeoutException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.transport.TransportUnavailableException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * Whether running the command again may end [failure]: a store or the network was unavailable or timed out, as
 * [failure] or one of its causes says. The run then ends with [MigrateData.UNAVAILABLE], which a Job runs again from
 * the checkpoint. Any other failure, a configuration the tool cannot read, a refusal or a defect, ends the run with
 * [MigrateData.FAILED], which fails a Job at once: a retry would only delay the answer, or import a batch again before
 * anyone looks.
 *
 * - **The SPI's:** [StorageUnavailableException] (a store [me.ahoo.wow.spi.StorageBusyException] at its capacity
 *   included), [StorageTimeoutException], [TransportUnavailableException].
 * - **The network's:** a socket that failed or closed ([SocketException], a refused connection included), one that
 *   timed out ([InterruptedIOException]), a host the resolver did not find ([UnknownHostException]). A local file the
 *   tool cannot read or write is not among them: a missing one is the configuration's, a full disk the machine's.
 * - **JDBC's:** a transient failure ([SQLTransientException], a pool that timed out waiting for a connection included),
 *   a recoverable one ([SQLRecoverableException]), or the SQL state class `08`, a connection exception.
 * - **MongoDB's:** a socket that failed ([MongoSocketException]), a server not selected in time
 *   ([MongoTimeoutException]), a statement stopped at its `maxTimeMS` ([MongoExecutionTimeoutException]).
 *
 * A store named wrongly reads as one that is down, since a driver reports both as unreachable: the Job's
 * `backoffLimit` bounds the retries.
 */
internal fun retryable(failure: Throwable): Boolean =
    generateSequence(failure, Throwable::cause).take(MAX_CAUSES).any(::unavailable)

private fun unavailable(failure: Throwable): Boolean = when (failure) {
    is StorageUnavailableException, is StorageTimeoutException, is TransportUnavailableException -> true
    is SocketException, is InterruptedIOException, is UnknownHostException -> true
    is SQLTransientException, is SQLRecoverableException -> true
    is SQLException -> failure.sqlState?.startsWith(CONNECTION_EXCEPTION) == true
    is MongoSocketException, is MongoTimeoutException, is MongoExecutionTimeoutException -> true
    else -> false
}

/** The SQL state class of a connection exception (SQL:2016, `08xxx`). */
private const val CONNECTION_EXCEPTION = "08"

/** How deep a cause chain is read: past that, a chain that loops back on an earlier cause. */
private const val MAX_CAUSES = 32
