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

package me.ahoo.wow.gradle

import io.mockk.every
import io.mockk.mockk
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue

/** A work queue that runs each item in process, as the worker would. */
internal fun inProcessQueue(project: Project): WorkQueue {
    val queue = mockk<WorkQueue>()
    queue.runs<ManifestToolsWork.CompleteFragment.Parameters>(project, ManifestToolsWork.CompleteFragment::class.java) {
        object : ManifestToolsWork.CompleteFragment() {
            override fun getParameters() = it
        }
    }
    queue.runs<ManifestToolsWork.Merge.Parameters>(project, ManifestToolsWork.Merge::class.java) {
        object : ManifestToolsWork.Merge() {
            override fun getParameters() = it
        }
    }
    queue.runs<ManifestToolsWork.GenerateContract.Parameters>(project, ManifestToolsWork.GenerateContract::class.java) {
        object : ManifestToolsWork.GenerateContract() {
            override fun getParameters() = it
        }
    }
    queue.runs<ManifestToolsWork.AssembleContract.Parameters>(project, ManifestToolsWork.AssembleContract::class.java) {
        object : ManifestToolsWork.AssembleContract() {
            override fun getParameters() = it
        }
    }
    queue.runs<ManifestToolsWork.GenerateContractSources.Parameters>(
        project,
        ManifestToolsWork.GenerateContractSources::class.java,
    ) {
        object : ManifestToolsWork.GenerateContractSources() {
            override fun getParameters() = it
        }
    }
    queue.runs<ManifestToolsWork.GenerateOpenApi.Parameters>(project, ManifestToolsWork.GenerateOpenApi::class.java) {
        object : ManifestToolsWork.GenerateOpenApi() {
            override fun getParameters() = it
        }
    }
    queue.runs<ManifestToolsWork.CheckCompatibility.Parameters>(
        project,
        ManifestToolsWork.CheckCompatibility::class.java,
    ) {
        object : ManifestToolsWork.CheckCompatibility() {
            override fun getParameters() = it
        }
    }
    queue.runs<ManifestToolsWork.RecordBaseline.Parameters>(project, ManifestToolsWork.RecordBaseline::class.java) {
        object : ManifestToolsWork.RecordBaseline() {
            override fun getParameters() = it
        }
    }
    return queue
}

/** Runs the work [action] makes of its parameters when [type] is submitted to this queue. */
private inline fun <reified P : WorkParameters> WorkQueue.runs(
    project: Project,
    type: Class<out WorkAction<P>>,
    crossinline action: (P) -> WorkAction<P>,
) {
    every { submit(type, any()) } answers {
        val parameters = project.objects.newInstance(P::class.java)
        secondArg<Action<P>>().execute(parameters)
        action(parameters).execute()
    }
}
