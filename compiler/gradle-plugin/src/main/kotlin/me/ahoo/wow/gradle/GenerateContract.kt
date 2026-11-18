package me.ahoo.wow.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.util.Properties
import javax.inject.Inject

/**
 * Writes the peer contract of the domain module's contexts: its completed Manifest fragments
 * ([fragments], the output of [GenerateManifestFragment]) filtered down to what another service reads, naming the
 * service that hosts them when the module declares it ([service]). The module offers it as the variant
 * `wowContractElements`, and the api modules that declare the module in `wowContract(...)` package it
 * ([AssembleContract]).
 *
 * A context whose command or event classes are in a project of the module's class path that does not pair with it
 * ([unpaired]: no `wowContract(project("<module>"))` there, or no `me.ahoo.wow.api`, as its [pairing] file says) fails
 * the task, naming the line to add: the contract would reach no api module, and no peer would see the context. It runs in an isolated worker with
 * the tools, like [MergeManifest].
 */
@CacheableTask
internal abstract class GenerateContract @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    /** The module's completed fragments, `META-INF/wow/manifest/<module>.json`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fragments: ConfigurableFileCollection

    /**
     * The service that hosts the module's contexts (`wow { service = "…" }`), which a peer
     * finds by its name; none when the module does not declare it.
     */
    @get:Input
    @get:Optional
    abstract val service: Property<String>

    /** The module's project path, which a failure names. */
    @get:Input
    abstract val module: Property<String>

    /** The module's compile class path entries that come from projects of the build, each to its project's path. */
    @get:Internal
    abstract val classpathProjects: MapProperty<String, String>

    /** The projects of [classpathProjects], whose pairing changes the outcome. */
    @get:Input
    abstract val classpathProjectPaths: ListProperty<String>

    /**
     * The pairing files of the api modules among those projects (`wowPairingElements`): the domain modules each
     * declares, `domains` comma-separated.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pairing: ConfigurableFileCollection

    /** The project each of [pairing]'s files comes from, by its path. */
    @get:Internal
    abstract val pairingProjects: MapProperty<String, String>

    /** The tools that write the contract: the `wowTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    /** The contract, a Manifest that names [service] alone; with no context when the module has no fragment. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        submit(workers.classLoaderIsolation { spec -> spec.classpath.from(tools) })
    }

    /**
     * The entries of [classpathProjects] whose project does not pair with [module]: no pairing file of it lists the
     * module.
     */
    internal fun unpaired(): Map<String, String> {
        val module = module.get()
        val paired = pairingProjects.get().filter { (file, _) ->
            val properties = Properties().apply { File(file).inputStream().use(::load) }
            module in properties.getProperty(ApiRole.PAIRING_DOMAINS, "").split(',')
        }.values.toSet()
        return classpathProjects.get().filterValues { it !in paired }
    }

    internal fun submit(queue: WorkQueue) {
        queue.submit(ManifestToolsWork.GenerateContract::class.java) { parameters ->
            parameters.fragments.from(fragments)
            parameters.service.set(service)
            parameters.module.set(module)
            parameters.unpaired.set(unpaired())
            parameters.output.set(outputFile)
        }
    }
}
