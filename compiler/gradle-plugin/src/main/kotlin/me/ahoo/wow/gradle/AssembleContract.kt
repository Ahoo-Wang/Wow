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

import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PeerContracts
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Assembles the peer contract an api module's jar carries, `META-INF/wow/contract.json`
 * ([PeerContracts.RESOURCE]) under [outputDirectory]: of the contracts of the domain modules that declare the api
 * module in `api(...)` ([contracts], their [GenerateContract] outputs), the contexts whose command and event classes are
 * all among the api module's [classes] ([PeerContracts.assemble]). A context with none of them is another api's.
 *
 * The build fails, naming the classes, when a context has some of its classes in the api module and others outside
 * it, or when two domain modules declare one of its contexts. With no context of its own, the api module carries no
 * contract.
 */
@CacheableTask
abstract class AssembleContract : DefaultTask() {
    /** The contracts of the domain modules paired with this api module. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val contracts: ConfigurableFileCollection

    /** The api module's compiled classes. */
    @get:Classpath
    abstract val classes: ConfigurableFileCollection

    /** The api module, as the build names it in a failure. */
    @get:Input
    abstract val owner: Property<String>

    /** Where `META-INF/wow/contract.json` goes, which the api module's jar includes. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val contracts = contracts.files.sorted().map { ManifestFragment(it.path, ContractJson.read(it)) }
        val directories = classes.files.filter { it.isDirectory }
        val contract = try {
            PeerContracts.assemble(owner.get(), contracts) { binary ->
                val path = binary.replace('.', '/') + ".class"
                directories.any { File(it, path).isFile }
            }
        } catch (failure: IllegalStateException) {
            throw GradleException(failure.message!!, failure)
        }
        contract?.let { ContractJson.write(it, File(output, PeerContracts.RESOURCE)) }
    }
}
