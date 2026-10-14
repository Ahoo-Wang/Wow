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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.contract.ContractException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.net.URLClassLoader

/**
 * The peers a host reads on a class path several services share (DX gap G21): another server on it carries this
 * service's Manifest as its own peer, which is no peer of this one; two other services that claim one context still
 * conflict.
 */
class SharedClassPathPeersTest {
    @TempDir
    lateinit var directory: File

    /** A class loader over the test's own, with the peer Manifests [peers] (a service and its contexts) beside it. */
    private fun withPeers(vararg peers: Pair<String, List<String>>): ClassLoader {
        val peersDirectory = File(directory, "META-INF/wow/peers").apply { mkdirs() }
        peers.forEach { (service, contexts) ->
            val declared = contexts.joinToString(",") { """{"name":"$it"}""" }
            File(peersDirectory, "$service.json")
                .writeText("""{"format":1,"service":{"name":"$service"},"contexts":[$declared]}""")
        }
        File(peersDirectory, "index").writeText(peers.joinToString("\n", postfix = "\n") { "${it.first}.json" })
        return URLClassLoader(arrayOf(directory.toURI().toURL()), javaClass.classLoader)
    }

    private fun start(classLoader: ClassLoader): Host = Host(
        SpringApplicationBuilder(HostTestApp::class.java)
            .resourceLoader(DefaultResourceLoader(classLoader))
            .properties(hostProperties + ("wow.contexts.include" to BANK))
            .run(),
    )

    @Test
    fun `a host should start beside another server's copy of its own Manifest`() {
        start(withPeers("bank-service" to listOf(BANK), "ledger-service" to listOf("ledger"))).use { host ->
            host.node.contexts.keys.assert().containsExactly(BANK)
            val contracts = host.context.getBean(ContractDirectory::class.java)
            contracts.peers.map { it.manifest.service?.name }.assert().containsExactly("ledger-service")
            contracts.isLocal(BANK).assert().isTrue()
            contracts.serviceOf("ledger").assert().isEqualTo("ledger-service")
        }
    }

    @Test
    fun `a host should not start when two other services claim one context`() {
        val failure = assertThrows<Exception> {
            start(withPeers("ledger-service" to listOf("ledger"), "audit-service" to listOf("audit", "ledger")))
        }
        val conflict = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<ContractException>().first()
        conflict.problems.single().assert()
            .startsWith("context ledger is declared by the peers ledger-service (")
            .contains("audit-service (")
    }
}
