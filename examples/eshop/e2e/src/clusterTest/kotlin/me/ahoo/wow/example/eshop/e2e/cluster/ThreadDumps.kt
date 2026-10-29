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

import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread dumps of the platform's JVMs, taken while something looks stuck: a pod Kubernetes does not count as ready
 * outside a fault, a liveness probe that failed, or a scenario that failed or ran out of its budget. A node whose
 * store calls stopped returning logs nothing more, so its threads, virtual ones included, are the evidence.
 *
 * Each is `jcmd 1 Thread.dump_to_file -format=json` in the pod (the start script execs the JVM, so it is the pod's
 * process 1), read back into [directory] as `<pod>-<n>-<reason>.json`: at most [PER_POD] per pod, a [COOLDOWN] apart,
 * so a pod that stays unready, or a run of failing scenarios, does not fill the artifact.
 */
class ThreadDumps(private val kube: Kube, private val directory: File, private val log: (String) -> Unit) {
    private val taken = ConcurrentHashMap<String, MutableList<Instant>>()

    /** Dumps [pod]'s threads for [reason], unless it had its share or one lately. */
    fun dump(pod: String, reason: String) {
        val now = Instant.now()
        val times = taken.computeIfAbsent(pod) { mutableListOf() }
        val number = synchronized(times) {
            val recent = times.lastOrNull()?.let { Duration.between(it, now) < COOLDOWN } == true
            if (times.size >= PER_POD || recent) {
                return
            }
            times += now
            times.size
        }
        val made = kube.exec(pod, "jcmd", "1", "Thread.dump_to_file", "-overwrite", "-format=json", FILE)
        val read = if (made.ok) kube.exec(pod, "cat", FILE, timeout = READ_TIMEOUT) else made
        if (!read.ok) {
            log("no thread dump of $pod ($reason): ${read.err.ifBlank { read.out }.trim().take(ERROR_LENGTH)}")
            return
        }
        val name = "$pod-$number-${reason.lowercase().replace(NOT_NAME, "-").trim('-')}.json"
        File(directory, name).apply { parentFile.mkdirs() }.writeText(read.out)
        log("dumped the threads of $pod ($reason) to $name")
    }

    /** Dumps every platform pod that is not being deleted, for [reason]. */
    fun dumpAll(reason: String) {
        runCatching { kube.allPods() }.getOrDefault(emptyList())
            .filter { platform(it.name) && !it.deleting }
            .forEach { runCatching { dump(it.name, reason) } }
    }

    private companion object {
        const val FILE = "/tmp/wow-threads.json"
        const val PER_POD = 3
        const val ERROR_LENGTH = 200
        val COOLDOWN: Duration = Duration.ofMinutes(1)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(120)
        val NOT_NAME = Regex("[^a-z0-9]+")
    }
}
