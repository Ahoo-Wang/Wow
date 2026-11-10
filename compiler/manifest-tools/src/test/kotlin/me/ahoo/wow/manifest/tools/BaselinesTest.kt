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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.ADD_ITEM
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.BASELINE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.CART
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.TRANSFER_SPACE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.cart
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The baseline a service is held to ([Baseline]): the line a break needs, and the gate that reads it. */
class BaselinesTest {
    @Test
    fun `a break should need a later major, or a pre-release of a major's first version`() {
        Baseline.allowsBreaking(10, "10.1.0").assert().isFalse()
        Baseline.allowsBreaking(10, "10.0.1").assert().isFalse()
        Baseline.allowsBreaking(10, "10.1.0-rc.1").assert().isFalse()
        Baseline.allowsBreaking(10, "11.0.0").assert().isTrue()
        // Before a major's first release nothing is promised (decision D306): Wow's own 10.0.0 pre-releases.
        Baseline.allowsBreaking(10, "10.0.0-alpha.6").assert().isTrue()
        Baseline.allowsBreaking(null, "10.0.0-beta.1").assert().isTrue()
        // No version, or none to compare with: no break.
        Baseline.allowsBreaking(10, null).assert().isFalse()
        Baseline.allowsBreaking(null, "11.0.0").assert().isFalse()
        Baseline.allowsBreaking(10, "unspecified").assert().isFalse()
    }

    @Test
    fun `the gate should fail on no baseline, a break within the line and a stale baseline, naming the update`(
        @TempDir directory: File,
    ) {
        val baseline = File(directory, "manifest/baseline.json")
        val current = File(directory, "build/manifest.json")
        val report = File(directory, "build/report/compatibility.txt")
        val recordReport = File(directory, "build/report/baseline.txt")
        val owner = "project ':shop-server'"
        fun gate() = ManifestTools.checkCompatibility(baseline, current, report, owner)
        fun record(allowBreaking: Boolean = false) =
            ManifestTools.recordBaseline(current, baseline, allowBreaking, recordReport, owner)
        fun build(manifest: Manifest, version: String) =
            ManifestJson.write(manifest.copyWith(service = ServiceInfo("shop-service", version)), current)
        val broken = BASELINE.cart { it.copyWith(commands = it.commands - ADD_ITEM) }
        val additive = BASELINE.cart {
            it.copyWith(commands = it.commands + SystemCommands.lifecycle("shop", "cart", TRANSFER_SPACE))
        }

        build(BASELINE, "10.1.0")
        assertThrows<IllegalStateException> { gate() }.message.assert().isEqualTo(
            "project ':shop-server' has no Manifest baseline (baseline.json): run updateWowManifestBaseline of " +
                "project ':shop-server' to record the Manifest this build produces, review it and commit it.",
        )
        record()
        Baseline.read(baseline).line.assert().isEqualTo(10)
        Baseline.read(baseline).manifest.service.assert().isEqualTo(ServiceInfo("shop-service"))
        gate().assert().isZero()

        // A break within the line fails the gate, and the baseline refuses to record it.
        build(broken, "10.2.0")
        assertThrows<IllegalStateException> { gate() }.message.assert().contains(
            "The Manifest of project ':shop-server' has 1 breaking change(s) within its compatibility line 10 " +
                "(version 10.2.0):\n  - $CART.commands[AddItem]: command removed\n",
            "add a new event beside the old one",
            "a break needs the service's next major version, then run updateWowManifestBaseline of project ':shop-server'",
        ).doesNotContain(directory.path)
        assertThrows<IllegalStateException> { record() }.message.assert().contains(
            "project ':shop-server' refuses to record 1 breaking change(s) within its compatibility line 10",
            "for a pre-release, record it with -Pwow.manifest.allowBreaking",
        )

        // An additive change keeps the contract, but the baseline must record it.
        build(additive, "10.2.0")
        assertThrows<IllegalStateException> { gate() }.message.assert().contains(
            "The Manifest baseline of project ':shop-server' does not record the Manifest this build produces " +
                "(1 additive change(s), 0 breaking one(s) a new major or an open pre-release allows; recorded on " +
                "the line 10, built on 10;",
            "run updateWowManifestBaseline of project ':shop-server', review the diff and commit it.",
        )
        record()
        gate().assert().isZero()

        // The next major may break: the gate allows it, and the baseline records it on the new line.
        build(broken, "11.0.0")
        assertThrows<IllegalStateException> { gate() }.message.assert().contains("does not record the Manifest")
        report.readText().assert().contains("breaking changes allowed")
        record()
        Baseline.read(baseline).line.assert().isEqualTo(11)
        gate().assert().isZero()

        // A pre-release of a major's first version keeps the line open: a break passes and records.
        build(BASELINE, "11.0.0-beta.1")
        record()
        gate().assert().isZero()

        // Within a closed line, -Pwow.manifest.allowBreaking records a break, and the report says so.
        build(broken, "11.1.0")
        record(allowBreaking = true)
        recordReport.readText().assert().contains(
            "With -Pwow.manifest.allowBreaking: recorded 1 breaking change(s) within the line",
        )
    }

    @Test
    fun `a service with no version should record no line, and break nothing`(@TempDir directory: File) {
        val baseline = File(directory, "manifest/baseline.json")
        val current = File(directory, "build/manifest.json")
        val report = File(directory, "build/report/compatibility.txt")
        val recordReport = File(directory, "build/report/baseline.txt")
        val owner = "project ':shop-server'"
        fun gate() = ManifestTools.checkCompatibility(baseline, current, report, owner)
        fun record() = ManifestTools.recordBaseline(current, baseline, false, recordReport, owner)

        ManifestJson.write(BASELINE.copyWith(service = ServiceInfo("shop-service")), current)
        record()
        baseline.readText().assert().doesNotContain("\"${Baseline.LINE}\"")
        Baseline.read(baseline).line.assert().isNull()
        recordReport.readText().assert().startsWith("Recorded on the compatibility line (none) (version (none))")
        gate().assert().isZero()
        report.readText().assert().contains("Compatibility line (none), version (none): breaking changes fail")

        val broken = BASELINE.cart { it.copyWith(commands = it.commands - ADD_ITEM) }
        ManifestJson.write(broken.copyWith(service = ServiceInfo("shop-service")), current)
        assertThrows<IllegalStateException> { gate() }.message.assert()
            .contains("has 1 breaking change(s) within its compatibility line (none) (version (none))")
        assertThrows<IllegalStateException> { record() }.message.assert()
            .contains("refuses to record 1 breaking change(s) within its compatibility line (none) (version (none))")
    }
}
