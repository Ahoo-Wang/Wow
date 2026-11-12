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

package me.ahoo.wow.migrate

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The V10 aggregate models the state comparison and the snapshot writing rebuild states with: the services' own
 * models, so a state is rebuilt by the same evolve and upgrade functions the service runs.
 */
internal class MigrationModels(models: Collection<AggregateModel<*>>) {
    private val byType: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    /** The model of [type]; a [MigrationException] when none was found. */
    fun of(type: AggregateType): AggregateModel<Any> {
        @Suppress("UNCHECKED_CAST") // The kernel only hands a model the states it built or read as its state type.
        return byType[type] as AggregateModel<Any>?
            ?: throw MigrationException(
                "No V10 model of $type is on the class path: add the service's domain modules to \"classpath\".",
            )
    }

    companion object {
        /**
         * The models of every module registered on [classLoader] ([ModelProvider.load]), built with
         * [STAND_INS]: the tool runs no decide function, so a dependency the models take is never called.
         */
        fun load(classLoader: ClassLoader): MigrationModels = of(ModelProvider.load(classLoader))

        /** The models of [providers], built with [STAND_INS]. */
        fun of(providers: List<ModelProvider>): MigrationModels = try {
            MigrationModels(providers.flatMap { it.models(STAND_INS) })
        } catch (missing: MissingDependencyException) {
            val named = missing.name?.let { " named \"$it\"" }.orEmpty()
            throw MigrationException(
                "${missing.building ?: "A V10 model"} takes a ${missing.fullType.typeName}$named, which the tool " +
                    "can neither stand in for nor build: it stands in for an interface, which rebuilding a state " +
                    "never calls, and builds ${BUILT.keys.joinToString(" and ") { it.name }}. Take the dependency " +
                    "as an interface in the model, so the tool stands in for it.",
            )
        }

        /**
         * The class loader of the [classpath] entries over [parent]: a jar, or a directory, which holds classes, and
         * jars that are added too (a distribution's `lib`); [parent] itself when there is none.
         */
        fun classLoaderOf(classpath: List<String>, parent: ClassLoader): ClassLoader {
            if (classpath.isEmpty()) {
                return parent
            }
            val urls = classpath.map(Path::of).flatMap { path ->
                when {
                    !Files.exists(path) -> throw MigrationException("The class path entry $path does not exist.")
                    Files.isDirectory(path) -> listOf(path) + Files.list(path).use { files ->
                        files.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList()
                    }

                    else -> listOf(path)
                }
            }.map { it.toUri().toURL() }
            return URLClassLoader(urls.toTypedArray(), parent)
        }

        /** The standard JDK types the tool builds for a model, which rebuilding a state never reads either. */
        private val BUILT: Map<Class<*>, () -> Any> = mapOf(
            Clock::class.java to { Clock.systemUTC() },
            ZoneId::class.java to { ZoneOffset.UTC },
        )

        /**
         * A dependency of a type of [BUILT], built, and a stand-in for each of an interface type: building a model only
         * hands them to the aggregate's factory, and a call to a stand-in, which only a decide function would make,
         * fails.
         */
        val STAND_INS: Dependencies = Dependencies { type, name ->
            val built = BUILT[type]
            if (built != null) {
                built()
            } else if (!type.isInterface) {
                null
            } else {
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                    when (method.name) {
                        "toString" -> "stand-in ${type.name}${name?.let { " \"$it\"" }.orEmpty()}"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments?.firstOrNull()
                        else -> throw MigrationException(
                            "The migration tool runs no decide function, so it cannot call ${type.name}.${method.name}.",
                        )
                    }
                }
            }
        }
    }
}
