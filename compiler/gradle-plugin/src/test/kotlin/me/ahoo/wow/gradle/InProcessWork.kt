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
import org.gradle.workers.WorkQueue

/** A work queue that runs each item in process, as the worker would. */
internal fun inProcessQueue(project: Project): WorkQueue {
    val queue = mockk<WorkQueue>()
    every { queue.submit(ManifestToolsWork.CompleteFragment::class.java, any()) } answers {
        val parameters = project.objects.newInstance(ManifestToolsWork.CompleteFragment.Parameters::class.java)
        secondArg<Action<ManifestToolsWork.CompleteFragment.Parameters>>().execute(parameters)
        object : ManifestToolsWork.CompleteFragment() {
            override fun getParameters() = parameters
        }.execute()
    }
    every { queue.submit(ManifestToolsWork.GenerateContractSources::class.java, any()) } answers {
        val parameters = project.objects.newInstance(
            ManifestToolsWork.GenerateContractSources.Parameters::class.java,
        )
        secondArg<Action<ManifestToolsWork.GenerateContractSources.Parameters>>().execute(parameters)
        object : ManifestToolsWork.GenerateContractSources() {
            override fun getParameters() = parameters
        }.execute()
    }
    every { queue.submit(ManifestToolsWork.Merge::class.java, any()) } answers {
        val parameters = project.objects.newInstance(ManifestToolsWork.Merge.Parameters::class.java)
        secondArg<Action<ManifestToolsWork.Merge.Parameters>>().execute(parameters)
        object : ManifestToolsWork.Merge() {
            override fun getParameters() = parameters
        }.execute()
    }
    return queue
}
