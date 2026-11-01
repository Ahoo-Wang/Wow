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

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * The KSP runs of a build, one at a time: every KSP task of a module the plugin configures uses this service, and its
 * one usage at a time keeps two runs from overlapping in a Gradle daemon. Only KSP waits; compiling and testing stay
 * parallel.
 *
 * KSP 2.3.13 runs every KSP task in the daemon, on one class loader. Its resolver and its symbol caches are per thread,
 * but the IntelliJ application environment beneath them, with the file systems it reads the class path through, is
 * shared by every run. With runs overlapping, a run may resolve none of a library it reads, the standard library
 * included: its aggregate roots and subscription processors then never validate, and nothing of them is generated, a
 * Manifest fragment and a contract without them. Remove it when KSP isolates its runs.
 */
internal abstract class KspRuns : BuildService<BuildServiceParameters.None> {
    companion object {
        const val NAME = "wowKspRuns"

        /** The build's service, registered once for every project that applies the plugin. */
        fun of(project: Project): Provider<KspRuns> =
            project.gradle.sharedServices.registerIfAbsent(NAME, KspRuns::class.java) {
                it.maxParallelUsages.set(1)
            }
    }
}
