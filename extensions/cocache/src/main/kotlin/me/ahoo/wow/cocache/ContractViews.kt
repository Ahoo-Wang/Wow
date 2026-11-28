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

package me.ahoo.wow.cocache

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.manifest.PeerContracts
import java.net.URL

/**
 * The state views the contract jars on a class path list: every api module's contract jar lists the `<State>View`s it
 * generates ([PeerContracts.STATE_VIEWS_RESOURCE]), and each view's `@StateView` names the aggregate it views. A
 * service's own api is on its class path too, so its own aggregates' views are among them. Every such list is read, a
 * shaded jar's concatenated one too: blank lines and a name listed twice are ignored.
 */
internal object ContractViews {
    /** The view class of each aggregate type the lists on [classLoader] name a view of. */
    fun onClassPath(classLoader: ClassLoader): Map<AggregateType, Class<*>> =
        classLoader.getResources(PeerContracts.STATE_VIEWS_RESOURCE).toList()
            .flatMap { list -> namesOf(list).map { list to it } }
            .distinctBy { it.second }
            .associate { (list, name) -> viewOf(list, name, classLoader) }

    private fun namesOf(list: URL): List<String> =
        list.openStream().bufferedReader().use { it.readLines() }.map(String::trim).filter(String::isNotEmpty)

    private fun viewOf(list: URL, name: String, classLoader: ClassLoader): Pair<AggregateType, Class<*>> {
        val view = try {
            Class.forName(name, false, classLoader)
        } catch (_: ClassNotFoundException) {
            throw IllegalStateException(
                "$list lists the state view $name, which is on no class path entry: the contract jar and the classes " +
                    "it lists are of different versions, or a shaded jar kept the list without them. Depend on one " +
                    "version of the api module's contract (Maven: the classifier contract)."
            )
        }
        val type = checkNotNull(StateView.typeOf(view)) {
            "$list lists $name, which carries no @StateView: the list names only the views the Wow Gradle plugin " +
                "generates. Build the api module with the Wow Gradle plugin rather than editing or merging the list " +
                "by hand."
        }
        return type to view
    }
}
