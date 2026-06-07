import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.zip.ZipFile

val jmhRuntimeClasspath = configurations.named("jmhRuntimeClasspath")

/**
 * Merge all META-INF/wow-metadata.json from the runtime classpath into a single valid JSON.
 * Without this, the JMH JAR contains duplicate entries that concatenate into invalid JSON,
 * causing MetadataSearcher to fail silently at runtime.
 *
 * Aggregate entries are deep-merged (non-null values take precedence) to avoid the API module's
 * type=null overwriting the domain module's type=full.class.Name.
 */
val mergedWowMetadata = layout.buildDirectory.file("tmp/wow-metadata-merged/META-INF/wow-metadata.json")
val mergeWowMetadata = tasks.register("mergeWowMetadata") {
    description = "Merge all wow-metadata.json from classpath into a single file."
    outputs.file(mergedWowMetadata)
    inputs.files(jmhRuntimeClasspath)
    doLast {
        val parser = JsonSlurper()
        val merged = mutableMapOf<String, Any>()
        val metadataContents = mutableListOf<String>()

        fun deepMutable(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { entry ->
                entry.key.toString() to deepMutable(entry.value)
            }.toMutableMap()

            is List<*> -> value.map { deepMutable(it) }.toMutableList()
            else -> value
        }

        jmhRuntimeClasspath.get().resolve()
            .filter { it.name.endsWith(".jar") || it.isDirectory }
            .forEach { file ->
                if (file.isDirectory) {
                    val meta = file.resolve("META-INF/wow-metadata.json")
                    if (meta.exists()) {
                        metadataContents.add(meta.readText())
                    }
                } else {
                    ZipFile(file).use { zip ->
                        zip.getEntry("META-INF/wow-metadata.json")?.let { entry ->
                            metadataContents.add(zip.getInputStream(entry).bufferedReader().readText())
                        }
                    }
                }
            }

        for (text in metadataContents) {
            try {
                @Suppress("UNCHECKED_CAST")
                val next = parser.parseText(text) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val contexts = next["contexts"] as? Map<String, Any> ?: continue
                @Suppress("UNCHECKED_CAST")
                val mergedContexts = merged.getOrPut("contexts") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                for ((ctxName, ctxValue) in contexts) {
                    val existing = mergedContexts[ctxName]
                    if (existing == null) {
                        mergedContexts[ctxName] = deepMutable(ctxValue)!!
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val existingMap = existing as MutableMap<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val newMap = ctxValue as Map<String, Any>
                        // Deep-merge aggregates (non-null values win)
                        @Suppress("UNCHECKED_CAST")
                        val existingAggregates = existingMap.getOrPut("aggregates") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val newAggregates = newMap["aggregates"] as? Map<String, Any> ?: emptyMap()
                        for ((aggName, aggValue) in newAggregates) {
                            val existingAgg = existingAggregates[aggName]
                            if (existingAgg == null) {
                                existingAggregates[aggName] = deepMutable(aggValue)!!
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                val existingAggMap = existingAgg as MutableMap<String, Any>
                                @Suppress("UNCHECKED_CAST")
                                val newAggMap = aggValue as Map<String, Any?>
                                for ((key, value) in newAggMap) {
                                    if (value != null) {
                                        val existingVal = existingAggMap[key]
                                        if (existingVal == null || value is String) {
                                            existingAggMap[key] = value
                                        } else if (value is List<*>) {
                                            @Suppress("UNCHECKED_CAST")
                                            val existingList = (existingVal as? List<String> ?: emptyList()).toMutableList()
                                            @Suppress("UNCHECKED_CAST")
                                            val newList = value as List<String>
                                            existingList.addAll(newList.filter { it !in existingList })
                                            existingAggMap[key] = existingList
                                        }
                                    }
                                }
                            }
                        }
                        // Merge scopes (union)
                        @Suppress("UNCHECKED_CAST")
                        val existingScopes = existingMap.getOrPut("scopes") { mutableListOf<String>() } as MutableList<String>
                        @Suppress("UNCHECKED_CAST")
                        val newScopes = newMap["scopes"] as? List<String> ?: emptyList()
                        existingScopes.addAll(newScopes.filter { it !in existingScopes })
                    }
                }
            } catch (_: Exception) {
                // skip invalid metadata
            }
        }

        val outputFile = mergedWowMetadata.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(merged)))
        logger.lifecycle("Merged ${metadataContents.size} wow-metadata.json files into ${outputFile.absolutePath}")
    }
}

val mergedJmhServicesRoot = layout.buildDirectory.dir("tmp/jmh-services-merged")
val jmhServiceFilesToMerge = listOf(
    "META-INF/services/tools.jackson.databind.JacksonModule",
    "META-INF/services/me.ahoo.wow.id.GlobalIdGeneratorFactory",
)
val mergeJmhServices = tasks.register("mergeJmhServices") {
    description = "Merge critical SPI service files from the JMH runtime classpath."
    outputs.dir(mergedJmhServicesRoot)
    inputs.files(jmhRuntimeClasspath)
    inputs.files(jmhServiceFilesToMerge.map { layout.projectDirectory.file("src/jmh/resources/$it") })
    doLast {
        val outputRoot = mergedJmhServicesRoot.get().asFile
        outputRoot.deleteRecursively()

        fun MutableSet<String>.addServiceProviders(text: String) {
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .forEach { add(it) }
        }

        for (servicePath in jmhServiceFilesToMerge) {
            val providers = linkedSetOf<String>()
            jmhRuntimeClasspath.get().resolve()
                .filter { it.name.endsWith(".jar") || it.isDirectory }
                .forEach { file ->
                    if (file.isDirectory) {
                        val serviceFile = file.resolve(servicePath)
                        if (serviceFile.exists()) {
                            providers.addServiceProviders(serviceFile.readText())
                        }
                    } else {
                        ZipFile(file).use { zip ->
                            zip.getEntry(servicePath)?.let { entry ->
                                providers.addServiceProviders(zip.getInputStream(entry).bufferedReader().readText())
                            }
                        }
                    }
                }

            val localServiceFile = file("src/jmh/resources/$servicePath")
            if (localServiceFile.exists()) {
                providers.addServiceProviders(localServiceFile.readText())
            }

            if (providers.isNotEmpty()) {
                val outputFile = outputRoot.resolve(servicePath)
                outputFile.parentFile.mkdirs()
                outputFile.writeText(providers.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
            }
        }
    }
}

tasks.named<Jar>("jmhJar") {
    isZip64 = true
    dependsOn(mergeWowMetadata)
    dependsOn(mergeJmhServices)
    // Exclude all wow-metadata.json from dependency JARs (they're duplicated)
    exclude("META-INF/wow-metadata.json")
    // Add the merged metadata file
    from(mergedWowMetadata) {
        into("META-INF")
    }
    eachFile {
        if (path in jmhServiceFilesToMerge && !file.absolutePath.startsWith(mergedJmhServicesRoot.get().asFile.absolutePath)) {
            exclude()
        }
    }
    from(mergedJmhServicesRoot)
}
