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
import me.ahoo.wow.spring.boot.WowAutoConfiguration.Companion.hasBuiltInSystemCommands
import org.junit.jupiter.api.Test

/**
 * A host without a command authorizer: its operator's routes, and Wow's system commands of the built-in aggregates,
 * which its public port refuses (UI-50).
 */
class HostParkedOpsAuthorizationTest {
    @Test
    fun `without an authorizer the operator's routes are served, and every read and action refused`() {
        startHost("wow.http.ops.enabled" to true).use { bare ->
            bare.get(OpsPaths.PARKED).status.assert().isEqualTo(403)
            // No CommandAuthorizer bean: the node's gateway has none, so the public port refuses the built-in
            // aggregates' system commands.
            bare.node.gateway.authorizes.assert().isFalse()
            val manifest = bare.node.manifest
            manifest.hasBuiltInSystemCommands().assert().isFalse()
            val withJobs = manifest.copyWith(
                contexts = manifest.contexts.map { it.copyWith(commands = SystemCommands.jobs(it.name)) },
            )
            withJobs.hasBuiltInSystemCommands().assert().isTrue()
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
            withRebuild.hasBuiltInSystemCommands().assert().isTrue()
        }
    }
}
