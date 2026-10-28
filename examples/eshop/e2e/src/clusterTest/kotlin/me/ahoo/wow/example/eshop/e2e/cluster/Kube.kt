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

package me.ahoo.wow.example.eshop.e2e.cluster

import me.ahoo.wow.example.eshop.e2e.json
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/** What a `kubectl` run printed and how it exited. */
data class Run(val exit: Int, val out: String, val err: String) {
    val ok: Boolean get() = exit == 0
}

/** One pod as `kubectl get pods -o json` lists it. */
data class Pod(val name: String, val ip: String?, val ready: Boolean, val restarts: Int, val deleting: Boolean)

/**
 * The cluster, through `kubectl` on the runner, in the current context's namespace: what the scenarios do to it
 * (scale, restart, delete, exec) and what the verdict reads from it (pods, logs).
 */
class Kube(private val log: (String) -> Unit) {
    fun run(vararg arguments: String, timeout: Duration = COMMAND_TIMEOUT): Run {
        val out = File.createTempFile("kubectl-", ".out")
        val err = File.createTempFile("kubectl-", ".err")
        try {
            val process = ProcessBuilder(listOf("kubectl") + arguments).redirectOutput(out).redirectError(err).start()
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return Run(TIMED_OUT, out.readText(), "kubectl ${arguments.joinToString(" ")} timed out after $timeout")
            }
            return Run(process.exitValue(), out.readText(), err.readText())
        } finally {
            out.delete()
            err.delete()
        }
    }

    /** Runs `kubectl` [arguments], failing unless it exits 0. */
    fun require(vararg arguments: String, timeout: Duration = COMMAND_TIMEOUT): String {
        val result = run(*arguments, timeout = timeout)
        check(result.ok) { "kubectl ${arguments.joinToString(" ")} exited ${result.exit}: ${result.err}${result.out}" }
        return result.out
    }

    /** The pods labelled `app.kubernetes.io/name=`[name]. */
    fun pods(name: String): List<Pod> = podsOf("app.kubernetes.io/name=$name")

    /** Every pod of the namespace. */
    fun allPods(): List<Pod> = podsOf(null)

    private fun podsOf(selector: String?): List<Pod> {
        val arguments = listOfNotNull("get", "pods", selector?.let { "-l$it" }, "-o", "json").toTypedArray()
        return json.readTree(require(*arguments))["items"].values().map(::pod)
    }

    private fun pod(item: JsonNode): Pod {
        val statuses = item["status"]["containerStatuses"]?.values()?.toList().orEmpty()
        return Pod(
            name = item["metadata"]["name"].asString(),
            ip = item["status"]["podIP"]?.asString(),
            ready = statuses.isNotEmpty() && statuses.all { it["ready"].asBoolean() },
            restarts = statuses.sumOf { it["restartCount"].asInt() },
            deleting = item["metadata"]["deletionTimestamp"] != null,
        )
    }

    fun scale(deployment: String, replicas: Int) {
        log("scale $deployment to $replicas")
        require("scale", "deployment/$deployment", "--replicas=$replicas")
    }

    fun rolloutRestart(deployment: String) {
        log("rolling restart of $deployment")
        require("rollout", "restart", "deployment/$deployment")
    }

    /** Waits for the rollout of [workload] (`deployment/x`, `statefulset/x`) to finish; whether it did. */
    fun awaitRollout(workload: String, timeout: Duration = ROLLOUT_TIMEOUT): Boolean =
        run("rollout", "status", workload, "--timeout=${timeout.seconds}s", timeout = timeout.plusSeconds(10)).ok

    /** Deletes [pod]: gracefully, or at once ([force]) as a crash would end it. */
    fun deletePod(pod: String, force: Boolean = false) {
        log("delete pod $pod${if (force) " (force, no grace)" else ""}")
        val arguments = listOf("delete", "pod", pod, "--wait=false") +
            if (force) listOf("--grace-period=0", "--force") else emptyList()
        require(*arguments.toTypedArray())
    }

    /** Runs [command] in [pod]'s first container. */
    fun exec(pod: String, vararg command: String, timeout: Duration = COMMAND_TIMEOUT): Run =
        run("exec", pod, "--", *command, timeout = timeout)

    /**
     * [pod]'s log, of its current container, or of the one before it ([previous]), each line after the time Kubernetes
     * received it (RFC 3339); empty when there is none.
     */
    fun logs(pod: String, previous: Boolean = false): String {
        val arguments = listOf("logs", pod, "--timestamps=true") + if (previous) listOf("--previous") else emptyList()
        return run(*arguments.toTypedArray(), timeout = LOG_TIMEOUT).let { if (it.ok) it.out else "" }
    }

    companion object {
        const val TIMED_OUT = -1
        val COMMAND_TIMEOUT: Duration = Duration.ofSeconds(60)
        val ROLLOUT_TIMEOUT: Duration = Duration.ofMinutes(10)
        val LOG_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
