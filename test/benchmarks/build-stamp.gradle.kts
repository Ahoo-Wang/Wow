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

// The build's stamp, applied by the benchmark's server and load generator: `wow-build-stamp` beside the installed
// distribution's bin and lib, the commit it was built from (`commit=<sha>`) and whether the tree had changes then
// (`dirty=yes|no`). bench.sh's stamp.sh reads it, so a run names the build it measured and does not measure a stale
// one. Written at every build: the tree's state is not a task input Gradle can see.
val buildStamp = tasks.register("buildStamp") {
    val stamp = layout.buildDirectory.file("stamp/wow-build-stamp")
    val root = rootDir
    outputs.file(stamp)
    outputs.upToDateWhen { false }
    doLast {
        fun git(vararg args: String): String? = runCatching {
            val process = ProcessBuilder("git", *args).directory(root).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            output.takeIf { process.waitFor() == 0 }
        }.getOrNull()
        val commit = git("rev-parse", "HEAD") ?: "unknown"
        val dirty = git("status", "--porcelain")?.isEmpty() != true
        stamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("commit=$commit\ndirty=${if (dirty) "yes" else "no"}\n")
        }
    }
}

extensions.configure<DistributionContainer> {
    named("main") { contents { from(buildStamp) } }
}
