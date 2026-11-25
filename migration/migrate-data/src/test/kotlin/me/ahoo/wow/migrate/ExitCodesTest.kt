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
import com.mongodb.MongoSocketReadException
import com.mongodb.MongoTimeoutException
import com.mongodb.ServerAddress
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.StorageTimeoutException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.transport.TransportUnavailableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.io.UncheckedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import java.time.Duration

/**
 * The process's exit code ([exitCodeOf]): [MigrateData.UNAVAILABLE], which a Job retries, for what a retry can cure, a
 * store or the network unavailable or timed out ([retryable]); [MigrateData.FAILED], which fails a Job at once, for
 * every other failure.
 */
class ExitCodesTest {
    private val err = ByteArrayOutputStream()

    private fun exitOf(failure: Exception): Int = exitCodeOf(PrintStream(err, true)) { throw failure }

    /** The exit code of `check` with the configuration at [config]. */
    private fun exitOf(config: Path): Int = exitCodeOf(PrintStream(err, true)) {
        MigrateData(PrintStream(ByteArrayOutputStream())).run(listOf("check", "--config", config.toString()))
    }

    @Test
    fun `a dropped connection ends the process with the code a Job retries, its stack printed`() {
        exitOf(UncheckedIOException(SocketException("Connection reset"))).assert().isEqualTo(MigrateData.UNAVAILABLE)
        err.toString().assert().contains("Connection reset").contains("\tat ")
    }

    @Test
    fun `a store or the network unavailable or timed out is retried, as each layer reports it`() {
        val address = ServerAddress("mongo", 27017)
        listOf(
            StorageUnavailableException("the log is down"),
            StorageBusyException("the pool is at its capacity"),
            StorageTimeoutException(Duration.ofSeconds(30)),
            TransportUnavailableException("no broker"),
            ConnectException("Connection refused"),
            SocketTimeoutException("Read timed out"),
            UnknownHostException("postgres"),
            SQLTransientConnectionException("The pool timed out waiting for a connection"),
            SQLRecoverableException("The connection was closed"),
            SQLException("An I/O error occurred while sending to the backend", "08006"),
            MongoSocketReadException("Prematurely reached end of stream", address),
            MongoTimeoutException("Timed out while waiting for a server"),
            MongoExecutionTimeoutException(50, "operation exceeded time limit"),
            // The store's failure under the tool's own: an append whose outcome stayed unknown.
            MigrationException("the append's outcome stayed unknown")
                .apply { initCause(StorageUnavailableException("down")) },
        ).forEach { failure ->
            exitOf(IllegalStateException("wrapped", failure)).assert().describedAs("$failure")
                .isEqualTo(MigrateData.UNAVAILABLE)
        }
        // The tool's refusal, caused by a store: a step's, as `reported` answers it, or one `run` did not answer.
        exitOf(MigrationException("stopped").apply { initCause(SocketTimeoutException("Read timed out")) })
            .assert().isEqualTo(MigrateData.UNAVAILABLE)
        err.toString().assert().contains("FAILED stopped")
    }

    @Test
    fun `a configuration the tool cannot read fails at once`(@TempDir directory: Path) {
        val missing = directory.resolve("missing.json")
        exitOf(missing).assert().isEqualTo(MigrateData.FAILED)
        err.toString().assert().contains(NoSuchFileException::class.java.name)
        val unknown = directory.resolve("unknown.json")
        Files.writeString(unknown, """{ "map": "m.json", "mistyped": true }""")
        exitOf(unknown).assert().isEqualTo(MigrateData.FAILED)
    }

    @Test
    fun `the tool's verdict and any other failure fail at once`() {
        exitCodeOf(PrintStream(err, true)) { MigrateData.DIFFERS }.assert().isEqualTo(MigrateData.DIFFERS)
        exitOf(MigrationException("refused")).assert().isEqualTo(MigrateData.FAILED)
        err.toString().assert().startsWith("FAILED refused\n")
        exitOf(IllegalStateException("a defect")).assert().isEqualTo(MigrateData.FAILED)
        exitOf(UncheckedIOException(NoSuchFileException("checkpoint.json"))).assert().isEqualTo(MigrateData.FAILED)
        exitOf(UncheckedIOException(IOException("No space left on device"))).assert().isEqualTo(MigrateData.FAILED)
        exitOf(SQLException("duplicate key value violates unique constraint", "23505")).assert()
            .isEqualTo(MigrateData.FAILED)
        exitOf(SQLException("a driver's failure with no SQL state")).assert().isEqualTo(MigrateData.FAILED)
        // A cause chain that loops back on itself is read as far as it goes once, and ends.
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        exitOf(first).assert().isEqualTo(MigrateData.FAILED)
    }

    @Test
    fun `each outcome has a code of its own, none the JVM's on an OutOfMemoryError`() {
        val codes = with(MigrateData) { listOf(OK, FAILED, USAGE_ERROR, DIFFERS, UNAVAILABLE) }
        codes.distinct().assert().hasSize(codes.size).doesNotContain(3)
    }
}
