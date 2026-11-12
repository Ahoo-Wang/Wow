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

// The rc drill's eShop on the Wow a release published (eshop-build.sh): an init script for a release's own build, so
// the release's files stay as they were tagged. The eShop declares Wow by its coordinates, as a user's build does
// (me.ahoo.wow:wow-spring-boot, ...), and the root build substitutes each with the project that publishes it (D363 ⑤).
// For the eShop's projects alone, this adds a later rule that sends every me.ahoo.wow module back to the version the
// release published, from Maven Central: their classpaths, the BOM they import and the compiler the Wow Gradle plugin
// adds are the published ones. The last rule that names a target wins, so it is added once each project's own build
// script has run. Everything else in the build is as the release's.
//
//   ./gradlew -I test/rc-drill/published-wow.init.gradle.kts -Prc-drill.wow-version=<version> <tasks>

// Read in the eShop's projects alone: the script is applied to every build of the invocation, the included build-logic
// too, which neither has an eShop project nor sees the property.
allprojects {
    if (!name.startsWith("eshop-")) return@allprojects
    val publishedVersion: String = providers.gradleProperty("rc-drill.wow-version").orNull
        ?: error("-Prc-drill.wow-version=<the release's version> is required")
    afterEvaluate {
        configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                all {
                    val selector = requested
                    if (selector is ModuleComponentSelector && selector.group == "me.ahoo.wow") {
                        val coordinates = "${selector.group}:${selector.module}:$publishedVersion"
                        // A BOM is requested as a platform: it stays one.
                        val category = selector.attributes.keySet().firstOrNull { it.name == "org.gradle.category" }
                        @Suppress("UNCHECKED_CAST")
                        val platform = category != null &&
                            "${selector.attributes.getAttribute(category as Attribute<Any>)}" == "platform"
                        useTarget(if (platform) platform(module(coordinates)) else module(coordinates), "the published Wow")
                    }
                }
            }
        }
    }
}
