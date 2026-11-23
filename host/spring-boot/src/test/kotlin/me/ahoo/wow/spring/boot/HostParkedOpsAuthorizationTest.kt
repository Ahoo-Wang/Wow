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
import me.ahoo.wow.http.ops.OpsPaths
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spring.boot.http.NodePublicPort.Companion.operationalCommands
import me.ahoo.wow.spring.boot.http.NodePublicPort.Companion.refusedWithoutAuthorizer
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A host without a command authorizer that decides Wow's operational commands: its operator's routes, and Wow's system
 * commands of the built-in aggregates, which its public port refuses (UI-50).
 */
class HostParkedOpsAuthorizationTest {
    @Test
    fun `without an authorizer the operator's routes are served, and every read and action refused`() {
        startHost("wow.http.ops.enabled" to true).use { bare ->
            bare.post("${OpsPaths.PARKED}/${OpsPaths.LIST}").status.assert().isEqualTo(403)
            // No CommandAuthorizer bean: the node's gateway has none, so the public port refuses the built-in
            // aggregates' system commands.
            bare.node.gateway.decidesOperational.assert().isFalse()
            val manifest = bare.node.manifest
            // Its aggregates' lifecycle commands are listed, and none of them is operational.
            manifest.contexts.flatMap { it.aggregates }.flatMap { it.commands }.any { it.system }.assert().isTrue()
            manifest.operationalCommands().assert().isEmpty()
            val withJobs = manifest.copyWith(
                contexts = manifest.contexts.map { it.copyWith(commands = SystemCommands.jobs(it.name)) },
            )
            withJobs.operationalCommands().assert().containsExactlyInAnyOrderElementsOf(SystemCommands.JOBS)
            // The startup says so, naming them, unless an authorizer decides them or the node routes none.
            withJobs.refusedWithoutAuthorizer(decides = false).assert().contains(SystemCommands.JOBS.first())
            withJobs.refusedWithoutAuthorizer(decides = true).assert().isNull()
            manifest.refusedWithoutAuthorizer(decides = false).assert().isNull()
            val withRebuild = manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(
                        aggregates = context.aggregates.map { aggregate ->
                            aggregate.copyWith(
                                commands = aggregate.commands + SystemCommands.rebuild(context.name, aggregate.name),
                            )
                        },
                    )
                },
            )
            withRebuild.operationalCommands().assert().containsExactly(SystemCommands.REBUILD_SNAPSHOT)
        }
    }

    @Test
    fun `an authorizer of another concern does not open the operator's routes`() {
        startHost(
            "wow.http.ops.enabled" to true,
            sources = listOf(HostTestApp::class.java, OneAccountRule::class.java),
        ).use { host ->
            host.node.gateway.decidesOperational.assert().isFalse()
            val refused = host.post("${OpsPaths.PARKED}/${OpsPaths.LIST}")
            refused.status.assert().isEqualTo(403)
            refused.body!!["detail"].asString().assert().contains("no CommandAuthorizer of the service decides")
        }
    }

    /** An application's authorizer of one account: it allows every other action, operational ones included. */
    @Configuration(proxyBeanMethods = false)
    class OneAccountRule {
        @Bean
        fun oneAccount(): CommandAuthorizer = CommandAuthorizer { _, action ->
            if (action.ref.id == "forbidden") Authorization.Denied.of("No one opens it.") else Authorization.Allowed
        }
    }
}
