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
import me.ahoo.wow.manifest.PeerContracts
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
 * The peer contracts a host reads on a class path several services share (DX gap G21, decision D234): the api jar of
 * its own context carries a contract of it, which is no peer of the node; two other contracts that declare one context
 * conflict.
 */
class SharedClassPathContractsTest {
    @TempDir
    lateinit var directory: File

    /** A class loader over the test's own, with an api jar's contract of each of [apis] (its contexts) beside it. */
    private fun withContracts(vararg apis: Pair<String, List<String>>): ClassLoader {
        val urls = apis.map { (api, contexts) ->
            val root = File(directory, api)
            val declared = contexts.joinToString(",") { """{"name":"$it"}""" }
            File(root, PeerContracts.RESOURCE).apply { parentFile.mkdirs() }
                .writeText("""{"format":1,"contexts":[$declared]}""")
            root.toURI().toURL()
        }
        return URLClassLoader(urls.toTypedArray(), javaClass.classLoader)
    }

    private fun start(classLoader: ClassLoader): Host = Host(
        SpringApplicationBuilder(HostTestApp::class.java)
            .resourceLoader(DefaultResourceLoader(classLoader))
            .properties(hostProperties + ("wow.contexts.include" to BANK))
            .run(),
    )

    @Test
    fun `a host should start beside the contract of its own context`() {
        start(withContracts("bank-api" to listOf(BANK), "ledger-api" to listOf("ledger"))).use { host ->
            host.node.contexts.keys.assert().containsExactly(BANK)
            val contracts = host.context.getBean(ContractDirectory::class.java)
            contracts.peers.map { it.source }.single().assert().endsWith("ledger-api/${PeerContracts.RESOURCE}")
            contracts.isLocal(BANK).assert().isTrue()
            contracts.hasContext("ledger").assert().isTrue()
        }
    }

    @Test
    fun `a host should not start when two contracts declare one context`() {
        val failure = assertThrows<Exception> {
            start(withContracts("ledger-api" to listOf("ledger"), "audit-api" to listOf("audit", "ledger")))
        }
        val conflict = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<ContractException>().first()
        conflict.problems.single().assert()
            .startsWith("context ledger is declared by the contracts ")
            .contains("ledger-api/${PeerContracts.RESOURCE} and ")
            .endsWith("audit-api/${PeerContracts.RESOURCE}")
    }
}
