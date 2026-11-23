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

package me.ahoo.wow.gradle

/**
 * The Helm the plugin renders the `wow-service` chart with: one version, pinned with the plugin, and the SHA-256 of its
 * archive for each platform the plugin fetches it for. A new Helm is a new plugin release.
 *
 * The digests are those Helm's release publishes (its release notes, and each archive's `.sha256sum` beside it on
 * `get.helm.sh`), compared with each other when this table was written. The plugin checks every archive it fetches
 * against this table, whatever served it ([HelmService]).
 */
internal object HelmRelease {
    /** The pinned version, as `helm version` prints it. */
    const val VERSION = "v4.3.1"

    /** Where Helm's archives are served: `<base>/helm-<version>-<os>-<arch>.<tar.gz|zip>`. */
    const val BASE_URL = "https://get.helm.sh"

    /** The SHA-256 of each archive, by its platform (`<os>-<arch>`, as Helm names them). */
    val DIGESTS: Map<String, String> = mapOf(
        "darwin-amd64" to "34bec4eba761ba5c16d1965ee6b8f5b0abe5993974a802274e2a4f0418893d89",
        "darwin-arm64" to "6b4e61c8cd27d99e5a801e63ff25dd5c70b36875c2cd659d3e9ce6b76fc23198",
        "linux-amd64" to "0403a88ae20913d11b10a2467b50087721fea7581736da6b9d3992f5357fe54b",
        "linux-arm64" to "8cf8f66ecbc252ac8f7f06cca68aba88508ddfde3a6a34feb1678ad1694b4f88",
        "windows-amd64" to "fcfd7b796dcf0f225abc40b5636a3b01f55f3de2fb1dfb18475df94e62c14112",
        "windows-arm64" to "373edaae86db70342ef39b014e2e7764ea8f9cff08714843daa5e55b5ee492f2",
    )

    /**
     * The platform, as Helm names it, of the JVM's [osName] and [osArch] (`os.name`, `os.arch`); null for an operating
     * system the plugin fetches no Helm for. An architecture is named as the JVM reports it on each system:
     * `amd64` or `x86_64` is `amd64`, `aarch64` or `arm64` is `arm64`; any other is kept, and [DIGESTS] has none.
     */
    fun platformOf(osName: String, osArch: String): String? {
        val os = when {
            osName.startsWith("Linux") -> "linux"
            osName.startsWith("Mac") || osName.startsWith("Darwin") -> "darwin"
            osName.startsWith("Windows") -> "windows"
            else -> return null
        }
        val arch = when (osArch) {
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> osArch
        }
        return "$os-$arch"
    }

    /** The archive of [platform]: a zip on Windows, a gzipped tar elsewhere. */
    fun archiveOf(platform: String): String =
        "helm-$VERSION-$platform." + if (platform.startsWith("windows-")) "zip" else "tar.gz"

    /** The binary's name within the archive of [platform], under the directory named after it. */
    fun binaryOf(platform: String): String = if (platform.startsWith("windows-")) "helm.exe" else "helm"
}
