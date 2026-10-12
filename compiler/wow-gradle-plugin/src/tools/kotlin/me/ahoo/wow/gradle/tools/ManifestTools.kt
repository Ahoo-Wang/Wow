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

package me.ahoo.wow.gradle.tools

import me.ahoo.wow.manifest.ManifestMerger
import me.ahoo.wow.manifest.ServiceInfo
import java.io.File

/**
 * What the plugin's workers run (decision D83): the plugin resolves these tools (victools, Jackson, the kernel) into
 * a configuration of the build's project and loads them in an isolated class loader, so none of them reaches the
 * build script's class path. The plugin calls these functions by name, through reflection, and passes only JDK
 * types across that boundary.
 */
object ManifestTools {
    /**
     * Completes the compiler's [fragment] into [output] ([SchemaIntrospector]). The classes it refers to are loaded
     * from this object's class loader, the worker's, which holds the module's classes.
     */
    @JvmStatic
    fun completeFragment(fragment: File, output: File, schemaOverrides: Map<String, String>) {
        val introspector = SchemaIntrospector(ManifestTools::class.java.classLoader, schemaOverrides)
        ManifestJson.write(introspector.complete(ManifestJson.read(fragment)), output)
    }

    /**
     * Merges the fragments found in the [classpath] entries into [output] ([ManifestMerger]); fails on a conflict or
     * on a schema reference no fragment resolves. Returns the number of fragments merged.
     */
    @JvmStatic
    fun merge(classpath: List<File>, serviceName: String, serviceVersion: String?, output: File): Int {
        val fragments = classpath.flatMap(ManifestJson::fragments)
        val manifest = ManifestMerger.merge(fragments, ServiceInfo(serviceName, serviceVersion))
        val unresolved = manifest.unresolvedSchemaRefs
        check(unresolved.isEmpty()) {
            "The Manifest refers to schemas no fragment holds: $unresolved. Apply the me.ahoo.wow plugin to the " +
                "modules that declare them, so their fragments are completed."
        }
        ManifestJson.write(manifest, output)
        return fragments.size
    }
}
