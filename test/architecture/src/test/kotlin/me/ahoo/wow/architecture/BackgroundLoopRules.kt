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

package me.ahoo.wow.architecture

import java.io.File

/**
 * The background loops of wow-runtime run through `launchLoop` or `launchWorker` (`health/NodeLoops.kt`, RT-5), which
 * make a loop's end the node's liveness: no loop of the node ends unseen.
 *
 * - [unboundedLoops]: a `while (true)` or `while (isActive)` (of the scope or the coroutine context) appears only in
 *   the functions of [BOUNDED], each a loop that ends of itself, and not a background loop of the node. A new
 *   background loop written as such a `while` is caught here, and goes through `launchLoop` instead.
 * - [unhandledScopes]: each `CoroutineScope(...)` names the node's handler on its line (`contextOf(`), builds on a
 *   parent scope's context (`.coroutineContext`), or is one of [GIVEN]: one whose context the node assembly gives with
 *   the handler, or one that launches no loop. A loop launched in any other has the node's `NodeFatals` to record its
 *   end.
 */
object BackgroundLoopRules {
    /** The `while`s of a loop that never ends of its own condition. */
    private val UNBOUNDED = Regex(
        """while\s*\(\s*(true|isActive|scope\.isActive|coroutineContext\.isActive|currentCoroutineContext\(\)\.isActive)\s*\)"""
    )

    private val FUNCTION = Regex(
        """^\s*(?:(?:private|internal|public|protected|override|suspend|inline|operator|tailrec)\s+)*fun\s+""" +
            """(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\("""
    )

    /**
     * The functions, by file under the runtime's sources and name, whose unbounded `while` ends of itself: each one's
     * reason. None is a background loop of the node.
     */
    val BOUNDED: Map<Pair<String, String>, String> = mapOf(
        ("Executor.kt" to "tryAcquire") to "a compare-and-set retried until it takes a permit or finds none",
        ("wait/WaitRegistry.kt" to "register") to "a compare-and-set retried until the wait is registered",
        ("wait/WaitCoordinator.kt" to "join") to "a stop's wait until no execution is left",
        ("admission/CommandGateway.kt" to "close") to "a close's drain, under its timeout",
        ("schedule/ScheduleJobRuntime.kt" to "send") to "one command sent again until it settles",
        ("access/AccessWalking.kt" to "stampPaced") to "one stamp tried again while the store's bulkhead refuses it",
        ("timer/TimerRunner.kt" to "dispatch") to "the timers due in one round of the timer loop, until none is",
        ("state/SharedStateReader.kt" to "rebuild") to "one state rebuilt, by this reader or another, until it is",
        ("state/SharedStates.kt" to "committedUpTo") to "a walk down one aggregate's pending versions",
        ("state/StateReads.kt" to "latest") to "one state read again until its version or its deadline",
        ("subscription/EntryHandling.kt" to "retried") to "one entry's attempts, up to the retry policy",
        ("subscription/PartitionPause.kt" to "awaitRunning") to "a handling's wait while its partition is paused",
        ("subscription/PartitionRunner.kt" to "awaitCaughtUp") to "a partition's catch-up, until every worker is",
        ("subscription/SubscriptionOptions.kt" to "retryForever") to "one read retried until it succeeds",
        ("subscription/SubscriptionRuntime.kt" to "claim") to "one partition's start, once its stop under way ends",
        ("subscription/SubscriptionWorker.kt" to "drain") to "one aggregate's lane, until it is empty",
        ("log/HandoffLog.kt" to "run") to "the flow of one tail, which the shared tail's worker collects",
        ("log/HandoffLog.kt" to "ownCommitAhead") to "one hand-over's wait for the commits ahead of it",
        ("log/HandoffLog.kt" to "drain") to "the entries one tail has in memory, until none is",
    )

    /**
     * The scopes, by file under the runtime's sources, whose context the node assembly gives with its handler
     * (`NodeFatals.contextOf` or `NodeWiring.contextOf`), or whose default context carries it: where it comes from; or
     * which launch no loop, and why.
     */
    val GIVEN: Map<String, String> = mapOf(
        "cluster/ServiceMembership.kt" to "NodeAssembly's membership, `fatals.contextOf(MEMBERSHIP, service)`",
        "cluster/PartitionOwnership.kt" to "ContextAssembly's and TopicSubscriptions' `contextOf(OWNERSHIP, group)`",
        "ledger/LedgerPublisher.kt" to "NodeAssembly's `wiring.contextOf(LEDGER, context)`",
        "log/PartitionAppendQueue.kt" to "ContextAssembly's `wiring.contextOf(APPEND_QUEUE, context)`",
        "admission/CommandGateway.kt" to "NodeAssembly's `wiring.contextOf(NODE, \"gateway\")`",
        "wait/WaitCoordinator.kt" to "launches no loop: its detached executions are one-shots, settled by the executor",
        "health/NodeHealth.kt" to "its default context, `node.fatals.contextOf(NODE, \"health\")`",
        "node/NodeSampler.kt" to "its default context, `node.fatals.contextOf(NODE, \"sampler\")`",
    )

    private const val SOURCES = "src/main/kotlin/me/ahoo/wow/runtime"

    /** The unbounded `while`s outside [BOUNDED] (or every one, [listedToo]), as `file:line: function`. */
    fun unboundedLoops(repositoryRoot: File, listedToo: Boolean = false): List<String> =
        sources(repositoryRoot).flatMap { (path, lines) ->
            var function: String? = null
            lines.mapIndexedNotNull { index, line ->
                FUNCTION.find(line)?.let { function = it.groupValues[1] }
                "$path:${index + 1}: $function".takeIf {
                    UNBOUNDED.containsMatchIn(line) && (listedToo || (path to function) !in BOUNDED)
                }
            }
        }

    /** The scopes that neither name the handler nor build on a parent's context, outside [GIVEN], as `file:line`. */
    fun unhandledScopes(repositoryRoot: File): List<String> = sources(repositoryRoot).flatMap { (path, lines) ->
        lines.mapIndexedNotNull { index, line ->
            "$path:${index + 1}".takeIf {
                SCOPE.containsMatchIn(line) && "contextOf(" !in line && ".coroutineContext" !in line && path !in GIVEN
            }
        }
    }

    /** `CoroutineScope(` made, not a type or a receiver named. */
    private val SCOPE = Regex("""(?<![\w.:])CoroutineScope\(""")

    /** The runtime's Kotlin sources, each by its path under [SOURCES], with its lines, comments blanked. */
    private fun sources(repositoryRoot: File): List<Pair<String, List<String>>> {
        val root = File(repositoryRoot, "${ArchitectureRules.RUNTIME.dir}/$SOURCES")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.map { file ->
            file.relativeTo(root).invariantSeparatorsPath to blankComments(file.readText()).lines()
        }.toList()
    }

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""(?m)(^|[^:"])//.*$""")

    private fun blankComments(source: String): String =
        source.replace(BLOCK_COMMENT) { match -> "\n".repeat(match.value.count { it == '\n' }) }
            .replace(LINE_COMMENT, "$1")
}
