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

package me.ahoo.wow.test.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.quickstart.BankServer
import me.ahoo.wow.kernel.memory.InMemoryTopics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.Environment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Several services in this JVM, on the quickstart's bank service. */
class WowLocalTest {
    @Test
    fun `local services should share one set of topics, each on its own ports and contexts`() {
        val local = wowLocal(LocalPorts.RANDOM, partitions = 2) {
            service<BankServer>("bank-a", listOf("bank"))
            service(BankServer::class.java, "bank-b", listOf("bank"), properties = mapOf("wow.tenant.default" to "b"))
        }
        val (a, b) = local.servers.values.toList()
        local.use {
            local.servers.keys.assert().containsExactly("bank-a", "bank-b")
            local.publicPorts.assert().isEqualTo(mapOf("bank-a" to a.publicPort, "bank-b" to b.publicPort))
            local.readyLine.assert()
                .isEqualTo("""wow-local ready {"bank-a":${a.publicPort},"bank-b":${b.publicPort}}""")
            b.bean<InMemoryTopics>().assert().isSameAs(a.bean<InMemoryTopics>())
            b.bean<Environment>().apply {
                getProperty("spring.application.name").assert().isEqualTo("bank-b")
                getProperty("wow.contexts.include").assert().isEqualTo("bank")
                getProperty("wow.log.partitions").assert().isEqualTo("2")
                getProperty("wow.tenant.default").assert().isEqualTo("b")
                activeProfiles.assert().containsExactly("dev")
            }
            // A service serves its own contexts: no peer of them.
            b.bean<Environment>().getProperty("wow.peers.bank.url").assert().isNull()
        }
        accepts(a.publicPort).assert().isFalse()
        accepts(b.publicPort).assert().isFalse()
    }

    @Test
    fun `a service should call the contexts of the services started before it at their internal ports`() {
        wowLocal(LocalPorts.RANDOM) { service<BankServer>("bank", listOf("bank")) }.use { local ->
            val bank = local.servers.getValue("bank")
            val service = WowLocal.LocalService(BankServer::class.java, "bank", listOf("bank"), 0, 0, emptyMap())
            WowLocal.peersOf(mapOf(service to bank), listOf("shop"))
                .assert().isEqualTo(mapOf("wow.peers.bank.url" to "http://127.0.0.1:${bank.internalPort}"))
            WowLocal.peersOf(mapOf(service to bank), listOf("bank")).assert().isEmpty()
        }
    }

    @Test
    fun `fixed ports should be each service's own`() {
        val (public, internal) = freePorts()
        wowLocal(LocalPorts.FIXED) { service<BankServer>("bank", listOf("bank"), public, internal) }.use { local ->
            local.servers.getValue("bank").publicPort.assert().isEqualTo(public)
            local.servers.getValue("bank").internalPort.assert().isEqualTo(internal)
        }
    }

    @Test
    fun `fixed ports should be refused to a service that names none, before any starts`() {
        val (public, internal) = freePorts()
        val refused = assertThrows<IllegalArgumentException> {
            wowLocal(LocalPorts.FIXED) {
                service<BankServer>("bank", listOf("bank"), public, internal)
                service<BankServer>("teller", listOf("bank"))
            }
        }
        refused.message.assert().contains("teller", "wow-local.ports")
        accepts(public).assert().isFalse()
        assertThrows<IllegalArgumentException> {
            wowLocal(
                LocalPorts.FIXED
            ) { service(BankServer::class.java, "teller", listOf("bank"), publicPort = public) }
        }.message.assert().contains("teller")
    }

    @Test
    fun `a service that fails to start should close the ones started before it`() {
        val (public, internal) = freePorts()
        val (brokenPublic, brokenInternal) = freePorts()
        assertThrows<RuntimeException> {
            wowLocal(LocalPorts.FIXED) {
                service<BankServer>("bank", listOf("bank"), public, internal)
                service<BankServer>("broken", listOf("nowhere"), brokenPublic, brokenInternal)
            }
        }
        accepts(public).assert().isFalse()
        accepts(internal).assert().isFalse()
    }

    @Test
    fun `a local run should be checked as it is built`() {
        assertThrows<IllegalArgumentException> { wowLocal(LocalPorts.RANDOM) {} }
        assertThrows<IllegalArgumentException> { wowLocal(LocalPorts.RANDOM, partitions = 0) {} }
        assertThrows<IllegalArgumentException> { wowLocal(LocalPorts.RANDOM) { service<BankServer>(" ", listOf("b")) } }
        assertThrows<IllegalArgumentException> { wowLocal(LocalPorts.RANDOM) { service<BankServer>("b", emptyList()) } }
        assertThrows<IllegalArgumentException> {
            wowLocal(LocalPorts.RANDOM) {
                service<BankServer>("bank", listOf("bank"))
                service<BankServer>("bank", listOf("bank"))
            }
        }
    }

    @Test
    fun `the ports should be fixed unless the system property says random`() {
        LocalPorts.of(null).assert().isEqualTo(LocalPorts.FIXED)
        LocalPorts.of(" ").assert().isEqualTo(LocalPorts.FIXED)
        LocalPorts.of("Fixed").assert().isEqualTo(LocalPorts.FIXED)
        LocalPorts.of("RANDOM").assert().isEqualTo(LocalPorts.RANDOM)
        assertThrows<IllegalArgumentException> { LocalPorts.of("any") }.message.assert().contains("wow-local.ports")
    }

    @Test
    fun `a run should print the ready line and exit when its standard input closes`() {
        val output = ByteArrayOutputStream()
        val exits = mutableListOf<Int>()
        val local = runWowLocal(
            LocalPorts.RANDOM,
            WowLocal.DEFAULT_PARTITIONS,
            { service<BankServer>("bank", listOf("bank")) },
            exitOnEof = true,
            input = ByteArrayInputStream("keep running\n".toByteArray()),
            output = PrintStream(output, true),
            errors = System.err,
            exit = { exits += it },
        )!!
        local.use {
            output.toString().trim().assert().isEqualTo(local.readyLine)
            exits.assert().containsExactly(0)
        }
    }

    @Test
    fun `a run whose service fails to start should say why and exit with 1`() {
        val errors = ByteArrayOutputStream()
        val exits = mutableListOf<Int>()
        val local = runWowLocal(
            LocalPorts.FIXED,
            WowLocal.DEFAULT_PARTITIONS,
            { service<BankServer>("bank", listOf("bank")) },
            exitOnEof = false,
            input = System.`in`,
            output = System.out,
            errors = PrintStream(errors, true),
            exit = { exits += it },
        )
        local.assert().isNull()
        errors.toString().assert().contains("wow-local failed to start", "bank")
        exits.assert().containsExactly(1)
    }

    @Test
    fun `a run that does not wait for its standard input should give back the services`() {
        runWowLocal(
            LocalPorts.RANDOM,
            WowLocal.DEFAULT_PARTITIONS,
            { service<BankServer>("bank", listOf("bank")) },
            exitOnEof = false,
            input = System.`in`,
            output = PrintStream(ByteArrayOutputStream(), true),
            errors = System.err,
            exit = { error("no exit expected: $it") },
        )!!.use { it.servers.keys.assert().containsExactly("bank") }
    }

    @Test
    fun `the ports should be the system property's when the run names none`() {
        withProperty(LocalPorts.PROPERTY, "random") {
            wowLocal { service(BankServer::class.java, "bank", listOf("bank")) }.use { local ->
                local.servers.getValue("bank").publicPort.assert().isPositive()
            }
        }
    }

    /**
     * The `main` of a local run, as [runWowLocal] runs it: its services listen once the ready line is printed, and stay
     * running until the JVM exits, which closes them, as a `main`'s do.
     */
    @Test
    fun `a main's run should print the ready line and leave its services running`() {
        val output = ByteArrayOutputStream()
        val out = System.out
        withProperty(LocalPorts.PROPERTY, "random") {
            System.setOut(PrintStream(output, true))
            try {
                runWowLocal { service<BankServer>("bank-main", listOf("bank")) }
            } finally {
                System.setOut(out)
            }
        }
        val port = Regex(""""bank-main":(\d+)""").find(output.toString())!!.groupValues[1].toInt()
        accepts(port).assert().isTrue()
    }

    private fun withProperty(name: String, value: String, run: () -> Unit) {
        val before = System.getProperty(name)
        System.setProperty(name, value)
        try {
            run()
        } finally {
            if (before == null) System.clearProperty(name) else System.setProperty(name, before)
        }
    }

    private fun freePorts(): Pair<Int, Int> = ServerSocket(0).use { first ->
        ServerSocket(0).use { second -> first.localPort to second.localPort }
    }

    private fun accepts(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
