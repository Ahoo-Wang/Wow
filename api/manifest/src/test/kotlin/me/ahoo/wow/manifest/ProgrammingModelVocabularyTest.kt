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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.wire.WireNames
import org.junit.jupiter.api.Test
import me.ahoo.wow.api.aggregate.Creation as ModelCreation
import me.ahoo.wow.api.aggregate.Ownership as ModelOwnership

/**
 * The Manifest states the programming model's vocabulary in its own types, since the contract depends on no module
 * of the model (decision D294): each pair names the same values, both ways.
 */
class ProgrammingModelVocabularyTest {
    @Test
    fun `the error table should name exactly the framework codes`() {
        FrameworkErrors.ALL.map { it.code }.toSet().assert().isEqualTo(FrameworkCodes.ALL)
    }

    @Test
    fun `creation, ownership and a job's values should name the programming model's, by their wire names`() {
        Creation.entries.map { it.name }.assert().isEqualTo(ModelCreation.entries.map { it.name })
        Ownership.entries.map { it.name }.assert().isEqualTo(ModelOwnership.entries.map { it.name })
        JobManifest.SCOPES.assert().isEqualTo(JobScope.entries.map(WireNames::of))
        JobManifest.MISFIRES.assert().isEqualTo(Misfire.entries.map(WireNames::of))
        Creation.entries.map(ManifestTree::wire).assert().isEqualTo(ModelCreation.entries.map(WireNames::of))
    }

    @Test
    fun `the system commands should be named as the programming model names them`() {
        SystemCommands.LIFECYCLE_PREFIX.assert().isEqualTo(Lifecycle.NAMESPACE + ".")
        SystemCommands.DELETE_AGGREGATE.assert().isEqualTo(Lifecycle.DELETE_AGGREGATE)
        Lifecycle.COMMANDS.keys.assert().allMatch { it.startsWith(SystemCommands.LIFECYCLE_PREFIX) }
        SystemCommands.JOBS.assert().containsExactly(ScheduleJobs.ENABLE, ScheduleJobs.RESCHEDULE, ScheduleJobs.DISABLE)
        CommandRoutes.JOBS.assert().isEqualTo(ScheduleJobs.ENABLE.substringBeforeLast('.'))
    }
}
