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

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Records the service's merged Manifest as its [baseline] (decision D76), the one [CheckManifestCompatibility]
 * compares with: run it when a version is published, and when a new major version resets the contract. Commit the
 * file it writes.
 */
@DisableCachingByDefault(because = "Copies one file into the project's sources")
abstract class UpdateManifestBaseline : DefaultTask() {
    /** The service's merged Manifest, what [MergeManifest] writes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** The committed baseline, [WowExtension.manifestBaseline]. */
    @get:OutputFile
    abstract val baseline: RegularFileProperty

    @TaskAction
    fun update() {
        manifest.get().asFile.copyTo(baseline.get().asFile, overwrite = true)
    }
}
