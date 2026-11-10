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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a run writes in a migration's checkpoints, and so which runs must not write them at once: one run of each role
 * at a time, while runs of different roles share the checkpoints, each writing its own entries (`tail` beside
 * `compare`). Its [partition] numbers its lease in the target's lease store, its [label] its lock file.
 */
internal enum class CheckpointRole(val label: String, val commands: String) {
    /**
     * The import and the tail, which write each aggregate's import and tail, the imported keys and the compensation;
     * and the export, which writes into the 9.x store they read.
     */
    IMPORT("import", "import, import --final, tail or export"),
    COMPARE("compare", "compare"),
    SNAPSHOT("snapshot", "snapshot or import --snapshots"),
    EXPORT("export", "export"),
    ;

    val partition: Int get() = ordinal
}

/** Who holds a lock: [command] on [host], process [pid], run [run] (a UUIDv7), as the errors of other runs name it. */
internal data class CheckpointHolder(val command: String, val host: String, val pid: Long, val run: String) {
    override fun toString(): String = "$command on $host (pid $pid, run $run)"

    companion object {
        /** This process running [command]: the host is the Pod's name in Kubernetes. */
        fun of(command: String): CheckpointHolder =
            CheckpointHolder(command, hostName(), ProcessHandle.current().pid(), GlobalIdGenerator.UUID_V7.next())

        private fun hostName(): String = try {
            InetAddress.getLocalHost().hostName
        } catch (_: UnknownHostException) {
            System.getenv("HOSTNAME") ?: "an unknown host"
        }
    }
}

/** The roles a run holds on its checkpoints while it runs. */
internal interface CheckpointLock {
    /**
     * The epoch of each role held: every write of checkpoints kept in the target carries them, and fails over a
     * later one ([CheckpointFence]). None for a file, whose locks the operating system holds until the process ends.
     */
    val epochs: Map<CheckpointRole, Long>

    /** Fails once this run no longer holds its roles, before it writes. */
    fun ensureHeld() = Unit

    /** A run that writes no checkpoint: a dry run's. */
    object None : CheckpointLock {
        override val epochs: Map<CheckpointRole, Long> = emptyMap()
    }
}

/**
 * The locks of a checkpoint file at [path]: one file per role, `<file>.<role>.lock`, each locked by the operating
 * system for the whole run, which frees it when the process ends however it ends, so a crashed run holds nothing. A
 * run that finds a role locked fails at once, naming the holder, which each run writes into the lock file it holds.
 */
internal class FileCheckpointLocks(path: Path, private val holder: CheckpointHolder) {
    private val file: Path = path.toAbsolutePath().normalize()

    /** Runs [block] holding [roles]; fails, naming the holder, when another run holds one of them. */
    suspend fun <T> holding(roles: Set<CheckpointRole>, block: suspend (CheckpointLock) -> T): T {
        if (roles.isEmpty()) {
            return block(CheckpointLock.None)
        }
        val held = mutableListOf<Pair<FileChannel, FileLock>>()
        try {
            roles.sorted().forEach { role -> held += lock(role) }
            return block(CheckpointLock.None)
        } finally {
            held.forEach { (channel, lock) ->
                lock.release()
                channel.close()
            }
        }
    }

    private fun lock(role: CheckpointRole): Pair<FileChannel, FileLock> {
        val lockFile = file.resolveSibling("${file.fileName}.${role.label}.lock")
        val channel = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            // Another run in this process holds it.
            null
        }
        if (lock == null) {
            channel.close()
            val recorded = Files.readString(lockFile).trim().ifEmpty { "another run" }
            throw MigrationException(
                "The checkpoint $file is in use: $recorded holds its ${role.label} lock ($lockFile). " +
                    "${inUse(role)}: stop that run, or wait for it to end.",
            )
        }
        channel.truncate(0)
        channel.write(ByteBuffer.wrap("$holder, since ${Instant.now()}\n".toByteArray(StandardCharsets.UTF_8)), 0)
        channel.force(true)
        return channel to lock
    }
}

/**
 * The leases of the checkpoints kept in the target under [name] (`"checkpoint": "target:<name>"`), in the target's own
 * lease store: group `wow.migrate.checkpoint/<name>`, one partition per role ([CheckpointRole.partition]), owned by
 * [holder] for [ttl] and renewed every [renewal] while the run lasts, then released.
 *
 * - A run that finds a role held waits for its lease to end, at most one [ttl] and one [renewal] more, re-reading it
 *   every [poll]: a crashed holder renews nothing, so its lease expires and the run takes it. When the holder renews
 *   it meanwhile, or another run takes it, the run fails, naming the holder.
 * - A run whose lease was taken over (it paused longer than the TTL, say) fails at its next write: [ensureHeld] once
 *   the renewal finds the lease gone or it expired, and, before that is known, the epoch the write carries
 *   ([CheckpointFence]).
 */
internal class TargetCheckpointLeases(
    private val store: LeaseStore,
    private val name: String,
    private val holder: CheckpointHolder,
    private val ttl: Duration = TTL,
    private val renewal: Duration = RENEWAL,
    private val poll: Duration = POLL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val report: (String) -> Unit = {},
    /** Where the renewal runs: a thread of its own by default. */
    private val renewing: CoroutineContext = Dispatchers.Default,
) {
    private val group = "$GROUP_PREFIX$name"

    /**
     * Runs [block] holding [roles], renewing their leases on a thread of its own, so a run whose own thread blocks
     * keeps them; releases them when [block] ends, however it ends, and those it took when it cannot take them all.
     *
     * The roles are taken one at a time, in their order, and each is renewed from the moment it is taken: waiting for a
     * later role, which a crashed run may hold for up to a lease, never lets an earlier one expire. [block] starts only
     * while every role is still held.
     */
    suspend fun <T> holding(roles: Set<CheckpointRole>, block: suspend (CheckpointLock) -> T): T = coroutineScope {
        if (roles.isEmpty()) {
            return@coroutineScope block(CheckpointLock.None)
        }
        val held = Held()
        val renewer = launch(renewing) {
            while (isActive) {
                delay(renewal)
                held.renew()
            }
        }
        try {
            roles.sorted().forEach { held.add(it, acquire(it)) }
            held.ensureHeld()
            block(held)
        } finally {
            renewer.cancel()
            withContext(NonCancellable) { release(held.roles) }
        }
    }

    private suspend fun release(roles: Collection<CheckpointRole>) {
        roles.forEach { role ->
            try {
                store.release(group, role.partition, holder.toString())
            } catch (@Suppress("TooGenericExceptionCaught") unavailable: RuntimeException) {
                // The lease ends at its expiry instead.
                report(
                    "The lease store did not release the ${role.label} lease of the checkpoint target:$name: ${unavailable.message}"
                )
            }
        }
    }

    private suspend fun acquire(role: CheckpointRole): Lease {
        val deadline = clock() + (ttl + renewal).inWholeMilliseconds
        var seen: Lease? = null
        while (true) {
            store.acquire(group, role.partition, holder.toString(), ttl)?.let { return it }
            val current = store.owners(group).firstOrNull { it.partition == role.partition }
            val renewed = current != null && seen != null &&
                (current.owner != seen.owner || current.expiresAt > seen.expiresAt)
            if (renewed || clock() >= deadline) {
                throw MigrationException(inUseMessage(role, current ?: seen))
            }
            if (seen == null && current != null) {
                report(
                    "Waiting for the ${role.label} lease of the checkpoint target:$name, which ${current.owner} holds " +
                        "until ${Instant.ofEpochMilli(current.expiresAt)}: a run that stopped renews it no more.",
                )
            }
            seen = current ?: seen
            delay(poll)
        }
    }

    private fun inUseMessage(role: CheckpointRole, lease: Lease?): String {
        val owner = lease?.owner ?: "another run"
        val until = lease?.let { ", renewed while it runs (now until ${Instant.ofEpochMilli(it.expiresAt)})" }.orEmpty()
        return "The checkpoint target:$name is in use: $owner holds its ${role.label} lease$until. " +
            "${inUse(role)}: stop that run, or wait for it to end."
    }

    /**
     * The leases a run holds, each from the moment it was taken ([add]); the renewal ([renew]) runs on another thread
     * than the run, so the leases are replaced whole, under [lock], and read without it.
     */
    private inner class Held : CheckpointLock {
        private val lock = Any()

        @Volatile
        private var leases: Map<CheckpointRole, Lease> = emptyMap()

        @Volatile
        private var lost: CheckpointRole? = null

        /** The roles taken so far, to release. */
        val roles: Set<CheckpointRole> get() = leases.keys

        override val epochs: Map<CheckpointRole, Long> get() = leases.mapValues { it.value.epoch }

        fun add(role: CheckpointRole, lease: Lease) = synchronized(lock) { leases = leases + (role to lease) }

        suspend fun renew() {
            val renewing = leases.keys
            if (renewing.isEmpty()) {
                return
            }
            val renewed = try {
                store.renew(group, renewing.map { it.partition }, holder.toString(), ttl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") unavailable: RuntimeException) {
                // The store did not answer: the next round tries again, and the lease ends at its expiry meanwhile.
                report(
                    "The lease store did not renew the leases of the checkpoint target:$name: ${unavailable.message}"
                )
                return
            }
            val byPartition = renewed.associateBy { it.partition }
            synchronized(lock) {
                lost = lost ?: renewing.firstOrNull { it.partition !in byPartition }
                leases = leases.mapValues { (role, lease) -> byPartition[role.partition] ?: lease }
            }
        }

        override fun ensureHeld() {
            val now = clock()
            val role = lost ?: leases.entries.firstOrNull { now >= it.value.expiresAt }?.key ?: return
            throw MigrationException(
                "This run lost its ${role.label} lease of the checkpoint target:$name: it was not renewed in time, " +
                    "and another run may hold it now. It stops without writing.",
            )
        }
    }

    companion object {
        const val GROUP_PREFIX = "wow.migrate.checkpoint/"
        val TTL: Duration = 30.seconds
        val RENEWAL: Duration = 10.seconds
        val POLL: Duration = 2.seconds
    }
}

/** What a run of [role] is told when another holds it. */
private fun inUse(role: CheckpointRole): String = "One ${role.commands} runs on a checkpoint at a time"
