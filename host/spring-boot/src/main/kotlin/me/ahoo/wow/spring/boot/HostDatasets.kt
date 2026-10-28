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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetChange
import me.ahoo.wow.query.dataset.QueryDatasetFiles
import me.ahoo.wow.query.dataset.QueryDatasetListener
import me.ahoo.wow.query.dataset.QueryDatasets
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.nio.file.Path

/**
 * The datasets a node serves from files: every `{context}.{dataset}.json` of the working
 * directory's `<config>/wow/dataset/` and of the classpath's `META-INF/wow/dataset/`, read by [QueryDatasetFiles], so
 * a working-directory file takes precedence over the classpath's of the same dataset.
 */
internal object HostDatasets {
    /** The default working directory of the dataset files, `wow.query.dataset.config-directory`. */
    const val DEFAULT_CONFIG_DIRECTORY: String = "config"

    /**
     * The datasets of the files under [configDirectory] and on [classLoader]'s classpath, as one runtime catalog that
     * datasets may join or leave while the node runs ([QueryDatasets.add], [QueryDatasets.remove]).
     */
    fun load(configDirectory: Path, classLoader: ClassLoader): QueryDatasets {
        val files = QueryDatasetFiles(configDirectory, classLoader)
        val names = files.loadDirectory().all.map(QueryDataset::name) + classpathNames(classLoader)
        return files.loadAll(names.distinct().sortedBy(AggregateType::toString))
    }

    /** The dataset names of the classpath's files, `META-INF/wow/dataset/{context}.{dataset}.json`. */
    private fun classpathNames(classLoader: ClassLoader): List<AggregateType> =
        PathMatchingResourcePatternResolver(classLoader)
            .getResources("classpath*:${QueryDatasetFiles.CLASSPATH_DIRECTORY}/*${QueryDatasetFiles.EXTENSION}")
            .mapNotNull { it.filename?.removeSuffix(QueryDatasetFiles.EXTENSION) }
            .map(QueryDataset::nameOf)

    /**
     * Makes [bindings] forget each dataset that leaves [datasets] (a replaced one is bound again on its own), so its
     * table metadata and statements are not kept for a dataset no longer served.
     */
    fun forgetOnRemoval(datasets: QueryDatasets, bindings: QueryBindings<QueryDataset>) {
        if (datasets === QueryDatasets.NONE) return
        datasets.listen(
            object : QueryDatasetListener {
                override fun apply(change: QueryDatasetChange) {
                    if (change is QueryDatasetChange.Removed) bindings.forget(change.dataset)
                }
            },
        )
    }
}
