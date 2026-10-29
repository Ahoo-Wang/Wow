package me.ahoo.wow.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Writes the OpenAPI 3.1 document of the service's public routes from its merged `manifest.json`, the
 * output of [MergeManifest], for offline tools: the document `GET /wow/openapi` serves, as far as the build knows it.
 * What only the running node knows is the host's: a route without an id it adds for a command a transformer takes,
 * the query models and datasets its backends serve, and the error statuses it configures. It runs in an isolated
 * worker with the tools, like [MergeManifest].
 */
@CacheableTask
internal abstract class GenerateOpenApi @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    /** The tools that write the document: the `wowTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        submit(workers.classLoaderIsolation { spec -> spec.classpath.from(tools) })
    }

    internal fun submit(queue: WorkQueue) {
        queue.submit(ManifestToolsWork.GenerateOpenApi::class.java) { parameters ->
            parameters.manifest.set(manifestFile)
            parameters.output.set(outputFile)
        }
    }
}
