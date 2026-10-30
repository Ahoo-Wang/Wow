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
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.query.policy.QueryPolicy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import java.net.URLClassLoader

/**
 * A command function that queries and sends (review PA-6, PA-9): the model takes the node's `QueryApi` and
 * `CommandClient` as dependencies, which the host gives it although the node's APIs are built from the models, and
 * reads as the command's caller. A read the
 * function gets wrong, or one the read side refuses, fails the command as any unexpected exception does: it is the
 * code's fault, not the caller's, so the caller is not told its command is invalid.
 *
 * The caller a command function reads as is the one the command records, rebuilt: its tenant, owner and space and
 * its operator, not the request's roles or ABAC attributes, which live only with the request; a policy that reads them
 * sees none ([LibrarianRole]). Trusted code reads past every policy, in every tenant.
 *
 * The host serves the bank's context and the [LibraryModels] module, which only this test's class loader lists.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostDecideQueriesTest {
    private val host: Host = run {
        val library = checkNotNull(javaClass.getResource("/host-test-library/")) { "No host-test-library resources." }
        val loader = URLClassLoader(arrayOf(library), javaClass.classLoader)
        Host(
            SpringApplicationBuilder(HostTestApp::class.java, LibraryPolicies::class.java)
                .resourceLoader(DefaultResourceLoader(loader))
                // A development profile: the requests name their callers, and their roles, in the Wow-* headers.
                .properties(hostProperties + ("spring.profiles.active" to "dev"))
                .run(),
        )
    }

    @AfterAll
    fun close() {
        host.close()
    }

    private fun lend(book: String, account: String, who: Map<String, String> = ALICE): Answer =
        host.post("/library/book/$book/lend-book", """{"account":"$account"}""", who)

    @Test
    fun `a command function should query the node's read side through the QueryApi its model takes (PA-6)`() {
        host.post("/bank/account/acct-1/open-account", """{"owner":"alice"}""", ALICE).status.assert().isEqualTo(OK)

        lend("b-1", "acct-1").status.assert().isEqualTo(OK)
        host.get("/library/book/b-1/state", LIBRARIAN_ALICE).body!!["state"]["account"].asString().assert()
            .isEqualTo("acct-1")
        // The read found no account: the function's own rejection.
        val rejected = lend("b-2", "nobody")
        rejected.status.assert().isEqualTo(CONFLICT)
        rejected.body!!["code"].asString().assert().isEqualTo(NO_ACCOUNT)
    }

    @Test
    fun `a read the command function gets wrong should fail the command, not reject it as invalid (PA-9)`() {
        // A state no aggregate of the node has: the read API's argument, the code's mistake.
        lend("b-3", MISREAD_ACCOUNT).also {
            it.status.assert().isEqualTo(FAILED)
            it.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_FAILED)
        }
        // A query the read side refuses: a filter on a field the account has not.
        lend("b-4", REFUSED_ACCOUNT).also {
            it.status.assert().isEqualTo(FAILED)
            it.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_FAILED)
        }
    }

    @Test
    fun `a command the function sends wrongly should fail the command, not reject it as invalid`() {
        // A command no aggregate of the node decides: the client cannot route it, the code's mistake.
        lend("b-7", SEND_UNROUTED).also {
            it.status.assert().isEqualTo(FAILED)
            it.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_FAILED)
        }
    }

    @Test
    fun `a command function should read as the caller the command records, without the request's roles (PA-6)`() {
        val count = """{"kind":"count","filter":{"op":"match-all"}}"""
        // The request's caller is a librarian: it reads the books itself, and a caller without the role does not.
        host.post("/library/book/snapshot/query", count, LIBRARIAN_ALICE).status.assert().isEqualTo(OK)
        host.post("/library/book/snapshot/query", count, ALICE).status.assert().isEqualTo(FORBIDDEN)
        // The caller rebuilt from its command has no role: the librarian's policy refuses the read, and the command fails.
        lend("b-5", COUNT_AS_CALLER, LIBRARIAN_ALICE).also {
            it.status.assert().isEqualTo(FAILED)
            it.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_FAILED)
        }
        // Trusted code reads past the policy, in the command's tenant: the function goes on, and finds no such account.
        lend("b-6", COUNT_TRUSTED, LIBRARIAN_ALICE).body!!["code"].asString().assert().isEqualTo(NO_ACCOUNT)
    }

    private companion object {
        val ALICE = mapOf(DevelopmentCallerResolver.USER to "alice")
        val LIBRARIAN_ALICE = ALICE + (DevelopmentCallerResolver.ROLES to LIBRARIAN)
        const val FORBIDDEN = 403
        const val OK = 200
        const val CONFLICT = 409
        const val FAILED = 500
    }
}

/** The library's query policy ([LibrarianRole]) on the test's host. */
@Configuration(proxyBeanMethods = false)
class LibraryPolicies {
    @Bean
    fun librarianRole(): QueryPolicy = LibrarianRole()
}
