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

/**
 * The cluster's network as the pods see it (G.3 ③ and the evidence of F8, F9): HTTP and TCP from inside a pod, with
 * bash's /dev/tcp, since the probe and service images have no HTTP client.
 */
class PodNetwork(private val kube: Kube, private val report: ClusterReport) {
    /**
     * From every eShop node, a TCP connection to every eShop node's internal port, by the address it advertises
     * (`<pod>.<service>-cluster`): within a service (forwards, wait signals) and across services (the basket's calls,
     * the signals of waits that cross contexts), as the internal NetworkPolicies admit the shop's own pods (D298).
     * Each connection is classified as served, refused (nothing listening), timed out (dropped on the way, as a
     * NetworkPolicy drops) or unresolved, with its time; the evidence for dropped wait signals (F8).
     */
    fun mesh(phase: String): String {
        val nodes = MESH.flatMap { (service, port) ->
            kube.pods(
                service
            ).filter { it.ready && !it.deleting }.map { MeshNode(service, port, it.name, checkNotNull(it.ip)) }
        }
        val pairs = nodes.flatMap { from -> nodes.filter { it.pod != from.pod }.map { from to it } }
        // What the nodes advertise, their pod's IP (decision D319), must be served...
        val advertised = pairs.map { (from, to) -> connect(from.pod, to.ip, to.port, "${from.pod} -> ${to.pod}") }
        // ... and the name deploy/ advertised before D319, <pod>.<service>-cluster, is recorded: a Deployment's pods
        // have none (finding F9).
        val named = pairs.map { (from, to) ->
            connect(from.pod, "${to.pod}.${to.service}-cluster", to.port, "${from.pod} -> ${to.pod} by name")
        }
        val failed = advertised.filter { it.second != "served" }
        val slowest = advertised.maxByOrNull { it.third }
        report.note(
            "internal mesh ($phase), by the pods' IPs: ${advertised.size - failed.size} of ${advertised.size} " +
                "connections served, the slowest ${slowest?.third} ms (${slowest?.first}); not served: " +
                failed.take(LISTED).joinToString { "${it.first} ${it.second} after ${it.third} ms" },
        )
        val byName = named.groupingBy { it.second }.eachCount()
        if (byName.keys != setOf("served")) {
            report.note(
                "internal mesh by pod name ($phase, F9): <pod>.<service>-cluster $byName, e.g. " +
                    "${named.first { it.second != "served" }}",
            )
        }
        check(failed.isEmpty()) {
            "${failed.size} of ${advertised.size} internal connections not served: ${failed.take(LISTED)}"
        }
        return "${advertised.size} connections across ${nodes.size} nodes served, the slowest in ${slowest?.third} ms"
    }

    private data class MeshNode(val service: String, val port: Int, val pod: String, val ip: String)

    /** A TCP connection from [pod] to [host]:[port]: [what], served, refused, timed out or unresolved, and its time. */
    private fun connect(pod: String, host: String, port: Int, what: String): Triple<String, String, Long> {
        val script = "s=\$(date +%s%3N); r=\$(timeout 3 bash -c 'exec 3<>/dev/tcp/$host/$port' 2>&1); " +
            "e=\$?; echo \"\$e \$(( \$(date +%s%3N) - s )) \$r\""
        val out = kube.exec(pod, "bash", "-c", script).out.trim()
        val exit = out.substringBefore(' ').toIntOrNull()
        val millis = out.substringAfter(' ').substringBefore(' ').toLongOrNull() ?: -1
        val outcome = when {
            exit == 0 -> "served"
            exit == TIMEOUT_EXIT -> "timed out"
            "refused" in out -> "refused"
            "not known" in out || "resolve" in out -> "unresolved"
            else -> "failed: $out"
        }
        return Triple(what, outcome, millis)
    }

    /**
     * One HTTP/1.1 request from [pod] to [host]:[port], written with bash's /dev/tcp (the probe pods have no HTTP
     * client): the status code, then the response; `000` when no connection was made within 5 seconds.
     */
    fun http(
        pod: String,
        host: String,
        port: Int,
        method: String,
        path: String,
        body: String = "",
        vararg headers: Pair<String, String>,
    ): String {
        val request = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n$body")
        }
        val script = "exec 3<>/dev/tcp/$host/$port && printf '%s' \"\$REQUEST\" >&3 && cat <&3"
        val result = kube.exec(pod, "env", "REQUEST=$request", "timeout", "5", "bash", "-c", script)
        val status = result.out.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)
        return if (result.ok && status != null) "$status ${result.out}" else "000 ${result.err.trim()}"
    }

    /** Whether [pod] gets any response from [host]:[port] within 5 seconds: its status, `000` when none. */
    fun reach(pod: String, host: String, port: Int): String = http(pod, host, port, "GET", "/wow/manifest")

    private companion object {
        const val LISTED = 10
        const val TIMEOUT_EXIT = 124

        /** The eShop's internal ports, the mesh of its nodes. */
        val MESH = listOf(
            "eshop-catalog" to 9091,
            "eshop-basket" to 9092,
            "eshop-ordering" to 9093,
            "eshop-inventory" to 9094,
            "eshop-payment" to 9095,
        )
    }
}
