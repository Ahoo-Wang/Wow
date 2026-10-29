package me.ahoo.wow.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Assembles the peer contract an api module's jar carries, `META-INF/wow/contract.json` ([WowRoles.CONTRACT_RESOURCE])
 * under [outputDirectory]: of the contracts of the domain modules the api module declares in `wowContract(...)`
 * ([contracts], their [GenerateContract] outputs), the contexts whose command and event classes are all among the api
 * module's [classes]. A context with none of them is another api's. A declared module of no contract (it compiles no
 * model) fails the task, naming it.
 *
 * The build fails, naming the classes, when a context has some of its classes in the api module and others outside
 * it, or when two domain modules declare one of its contexts, naming the domain modules ([owners]). With no context of
 * its own, the api module carries no contract. It runs in an isolated worker with the tools, like [MergeManifest].
 */
@CacheableTask
internal abstract class AssembleContract @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    /** The contracts of the domain modules paired with this api module. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val contracts: ConfigurableFileCollection

    /** The domain module each of [contracts] comes from, by the contract's path, as a failure names it. */
    @get:Internal
    abstract val owners: MapProperty<String, String>

    /** The api module's compiled classes. */
    @get:Classpath
    abstract val classes: ConfigurableFileCollection

    /** The api module, as the build names it in a failure. */
    @get:Input
    abstract val owner: Property<String>

    /** The tools that assemble the contract: the `wowTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    /** Where `META-INF/wow/contract.json` goes, which the api module's jar includes. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {
        submit(workers.classLoaderIsolation { spec -> spec.classpath.from(tools) })
    }

    internal fun submit(queue: WorkQueue) {
        queue.submit(ManifestToolsWork.AssembleContract::class.java) { parameters ->
            parameters.contracts.from(contracts)
            parameters.owners.set(owners)
            parameters.classes.from(classes)
            parameters.owner.set(owner.map { "project '$it'" })
            parameters.output.set(outputDirectory)
        }
    }
}
