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

// The V10 BOM, artifact wow-bom (D87, R.1); 9.x's :wow-bom left v10 with the 9.x code (D262). It constrains exactly the V10 libraries a release publishes (`v10BomConstrainedProjects` in the root build),
// never an example, a spike, a platform service or :architecture, whose coordinates are not on Maven Central.
@Suppress("UNCHECKED_CAST")
val v10BomConstrainedProjects = rootProject.ext.get("v10BomConstrainedProjects") as Iterable<Project>

dependencies {
    constraints {
        v10BomConstrainedProjects.forEach {
            api(it)
        }
    }
}
