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

package me.ahoo.wow.bi

internal data class ObservedBiDeployment(val objects: List<ObservedBiObject>) {
    init {
        val duplicateKeys = objects.groupingBy(ObservedBiObject::key)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .map { key -> "${key.database}.${key.name}" }
            .sorted()
        require(duplicateKeys.isEmpty()) {
            "Observed BI deployment contains duplicate catalog objects: ${duplicateKeys.joinToString()}"
        }
    }

    val ownedObjects: List<ObservedBiObject>
        get() = objects.filter { it.metadata != null }
}

internal data class ObservedBiObject(
    val database: String,
    val name: String,
    val engine: String,
    val engineFull: String = "",
    val createTableQuery: String = "",
    val metadata: BiObjectMetadata? = null,
) {
    val key: BiObjectKey = BiObjectKey(database, name)
}

internal data class BiObjectKey(val database: String, val name: String)

internal data class BiOwnedObject(
    val key: BiObjectKey,
    val kind: BiObjectKind,
)

internal enum class BiObjectKind {
    ANCHOR,
    STORE,
    VIEW,
    QUEUE,
    CONSUMER,
}

internal enum class BiDeploymentPhase {
    STABLE,
    RESETTING,
}
