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

/*
 * The Wow Gradle plugin as Wow's own build applies it (settings.gradle.kts: `pluginManagement { includeBuild(...) }`):
 * the sources of :gradle-plugin (compiler/gradle-plugin), compiled here so the examples, the platform services and the
 * test applications apply `me.ahoo.wow`, `me.ahoo.wow.api` and `me.ahoo.wow.service` as a user's build does. The
 * plugin depends on no Wow module (its tools run in workers over `wowTools`), so this build needs none; the root
 * build's dependency substitution resolves the coordinates the plugin adds to its projects. :gradle-plugin stays the
 * project that is tested, checked and published; this build publishes nothing.
 */
rootProject.name = "wow-gradle-plugin"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}
