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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.SourceFile

/**
 * Stand-ins for the 9.x types the model processor rejects, under their 9.x names, so the tests can write 9.x code
 * without 9.x `wow-api` on the classpath: a project may not reach both `wow-api` modules (decisions D85, D90).
 */
object NineXStubs {
    /** The 9.x annotations V10 removed (decisions D67, D68). */
    val REMOVED_ANNOTATIONS = listOf(
        "AggregateId", "AggregateName", "AggregateRoute", "AggregateVersion", "CommandRoute", "EntityObject", "Event",
        "Generated", "OnError", "OnStateEvent", "Order", "OwnerId", "Spaced", "StatelessSaga", "TenantId",
        "ValueObject", "VoidCommand",
    )

    private val annotations = SourceFile.kotlin(
        "NineXAnnotations.kt",
        "package me.ahoo.wow.api.annotation\n\n" +
            REMOVED_ANNOTATIONS.joinToString("\n") { "annotation class $it" },
    )

    private val command = SourceFile.kotlin(
        "NineXCommand.kt",
        """
        package me.ahoo.wow.api.command

        interface CommandResultAccessor

        interface CommandMessage<C : Any> {
            val body: C
        }
        """.trimIndent(),
    )

    val SOURCES: List<SourceFile> = listOf(annotations, command)
}
