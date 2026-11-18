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
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/**
 * The source roots of each source set, its Kotlin and its Java ones, as an input of its Kotlin compile and its KSP
 * task ([INPUT]): the roots' paths relative to the project, so a build cache entry stays relocatable, and the tasks run
 * again, once, when a source set's roots move. Both tasks read the Java sources too, a Java aggregate's among them.
 *
 * Gradle fingerprints sources by their paths relative to their root, so a source set switched to another root holding
 * the same files at the same relative paths (the staged migration's switch back to `src/`) leaves both tasks up to
 * date. Kotlin's and KSP's incremental caches are keyed by absolute paths, so they still describe the old root, and
 * the next incremental change reprocesses too little: KSP leaves out a file it did not hand the processor (the model
 * of an aggregate written without its context's default and names object) and Kotlin drops a test class it did not
 * recompile, the build green. A changed input property makes the next run non-incremental, which rebuilds both caches
 * from the new root. It costs nothing until a root moves.
 */
internal object SourceRoots {
    /** The input property of the source set's roots, on its Kotlin compile and its KSP task. */
    const val INPUT = "wowSourceRoots"

    fun track(project: Project) {
        project.pluginManager.withPlugin(WowPlugin.KOTLIN_JVM) {
            project.extensions.getByType(SourceSetContainer::class.java).configureEach { sourceSet ->
                val roots = project.provider { rootsOf(project, sourceSet) }
                val tasks = setOf(sourceSet.getCompileTaskName("kotlin"), sourceSet.getTaskName("ksp", "Kotlin"))
                project.tasks.matching { it.name in tasks }.configureEach { it.inputs.property(INPUT, roots) }
            }
        }
    }

    /** The Kotlin and Java roots of [sourceSet], relative to [project]'s directory when within it, sorted, once each. */
    internal fun rootsOf(project: Project, sourceSet: SourceSet): List<String> =
        ((sourceSet.extensions.getByName("kotlin") as SourceDirectorySet).srcDirs + sourceSet.java.srcDirs)
            .map { it.relativeToOrSelf(project.projectDir).invariantSeparatorsPath }
            .distinct()
            .sorted()
}
