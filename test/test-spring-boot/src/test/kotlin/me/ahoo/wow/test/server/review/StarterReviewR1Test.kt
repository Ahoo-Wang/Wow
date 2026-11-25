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

package me.ahoo.wow.test.server.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.example.quickstart.BankServer
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spring.boot.config.DevelopmentMode
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.WowHttp
import me.ahoo.wow.test.server.fakePeer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Deep review round 1 of the test kit: where the Spring test starter (`wowServer`, `fakePeer`) behaves otherwise than
 * the services it stands for, on the quickstart's bank service. Each case asserts what a deployed service does, so it
 * fails on the starter until the two agree.
 */
class StarterReviewR1Test {
    @Test
    fun `a fake peer should refuse a system command its callee's permits do not list, as the real peer does`(
        @TempDir contracts: Path,
    ) {
        // A real peer's call route checks the caller's permits (edge/http/.../command/CommandHandler.kt:133-143, the
        // permits of wow.callers from NodeInternalPort.kt:109,172-176): a system command (`wow.*`) no `wow.`-entry
        // lists is 403 wow.call.command-forbidden (edge/http/.../caller/CallPermits.kt:72-90). FakePeer registers a
        // route for every POST command of the contract, system ones included (FakePeer.kt:140-146), and answers each
        // with the rule, a success by default (:148-174, :88).
        val path = "/bank/account/acc-1/wow.lifecycle.restore-aggregate"
        val headers = mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to CALL)
        val (real, manifest) = wowServer<BankServer>("dev").use { bank ->
            bank.internal.post(path, "{}", headers) to bank.bean<Manifest>()
        }
        real.assertForbidden("the real bank-service")
        // The fake serves the real bank's own Manifest as its contract, as the bank's api jar would carry it.
        val resource = contracts.resolve(PeerContracts.RESOURCE)
        Files.createDirectories(resource.parent)
        Files.write(resource, WowJson.mapper.writeValueAsBytes(ManifestTree.write(manifest)))
        val fake = withContracts(contracts) { fakePeer("bank") }.use { bank ->
            WowHttp(bank.url).use { it.post(path, "{}", headers) }
        }
        fake.assertForbidden("the fake bank-service")
    }

    @Test
    fun `a test server started with no development profile should not be a development node`() {
        // A deployment of the bank's own configuration (no profile, no wow.internal.host) is no development node
        // (host/spring-boot/.../config/DevelopmentMode.kt:69-86): `wow.backend=auto` refuses without a durable backend
        // (core/runtime/.../store/BackendSelection.kt:86-88), the public port without a CallerResolver
        // (NodePublicPort.kt:85-118). wowServer binds `wow.internal.host=127.0.0.1` for every test (WowServer.kt:125),
        // which BackendSelection.memoryOptIn (:112-119) takes as development: the node starts in memory, anonymous.
        val started = runCatching { wowServer<BankServer>() }
        started.getOrNull()?.use { server ->
            server.bean<DevelopmentMode>().reason.assert().describedAs("why the node runs for development").isNull()
        }
        started.exceptionOrNull()?.let { failure ->
            generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().firstOrNull()
                .assert().describedAs("a deployment's refusal: $failure").isNotNull()
        }
    }

    /** What [block] makes with the contracts under [directory] on the thread's class path, as a test's are. */
    private fun <T> withContracts(directory: Path, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        return URLClassLoader(arrayOf(directory.toUri().toURL()), previous).use { loader ->
            thread.contextClassLoader = loader
            try {
                block()
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    private fun Answer.assertForbidden(peer: String) {
        status.assert().describedAs("$peer answered $text").isEqualTo(FORBIDDEN)
        problem!!.code.assert().isEqualTo("wow.call.command-forbidden")
    }

    private companion object {
        const val FORBIDDEN = 403

        /** The `Wow-Call` of a root command in tenant acme, as a calling service writes it. */
        val CALL: String = CallHeaders.format(
            CallContext("command-1", "acme", Scope.NONE, Causation("command-1", "command-1")),
        )
    }
}
