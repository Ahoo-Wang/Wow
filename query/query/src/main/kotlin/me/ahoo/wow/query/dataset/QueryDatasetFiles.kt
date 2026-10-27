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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Reads the dataset files (decision D75: datasets are declared in files, not in the Manifest), one per dataset, named
 * `{context}.{dataset}.json` in the [QueryDatasetProperties] format: from the working directory's
 * `<configDirectory>/wow/dataset/`, which takes precedence, else from the classpath's `META-INF/wow/dataset/`.
 *
 * Every failure to find, read or parse a file is a [QuerySchemaUnavailableException] whose cause says what is wrong.
 */
@ExperimentalWowApi
class QueryDatasetFiles @JvmOverloads constructor(
    configDirectory: Path = Path.of("config"),
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: QueryDatasetFiles::class.java.classLoader,
) {
    /** The working directory the dataset files are read from first. */
    val directory: Path = configDirectory.resolve(WOW_DIRECTORY).resolve(DATASET_DIRECTORY)

    /** The dataset [name] declares in its file. */
    fun load(name: AggregateType): QueryDataset {
        val key = "${name.context}.${name.name}"
        requireFileKey(key)
        val file = directory.resolve("$key$EXTENSION")
        if (file.isRegularFile()) return read(file.toString(), name) { Files.readString(file) }
        val resources = classLoader.getResources("$CLASSPATH_DIRECTORY/$key$EXTENSION").toList()
        require(resources.size <= 1) { "Dataset [$key] is declared more than once: $resources." }
        val resource = resources.singleOrNull()
            ?: throw QuerySchemaUnavailableException("Dataset [$key] has no declaration file.")
        return read(resource.toExternalForm(), name) { resource.readText() }
    }

    /** The datasets of [names], each [load]ed. */
    fun loadAll(names: Collection<AggregateType>): QueryDatasets = QueryDatasets(names.map(::load))

    /** Every dataset whose file is in the working [directory]; none when it does not exist. */
    fun loadDirectory(): QueryDatasets {
        if (!Files.isDirectory(directory)) return QueryDatasets()
        val names = Files.list(directory).use { files ->
            files.filter { it.isRegularFile() && it.extension == EXTENSION.removePrefix(".") }
                .map { QueryDataset.nameOf(it.nameWithoutExtension) }
                .sorted(compareBy(AggregateType::toString))
                .toList()
        }
        return loadAll(names)
    }

    // A file that fails to read or validate keeps its error as the cause.
    @Suppress("TooGenericExceptionCaught")
    private inline fun read(location: String, name: AggregateType, text: () -> String): QueryDataset = try {
        parseDatasetDocument(name, text())
    } catch (error: Exception) {
        throw QuerySchemaUnavailableException("Unable to read dataset [$location].", error)
    }

    private fun requireFileKey(key: String) {
        require('/' !in key && '\\' !in key && Path.of(key).name == key) { "Dataset name [$key] is not a file name." }
    }

    companion object {
        const val WOW_DIRECTORY = "wow"
        const val DATASET_DIRECTORY = "dataset"
        const val CLASSPATH_DIRECTORY = "META-INF/wow/dataset"
        const val EXTENSION = ".json"
    }
}
