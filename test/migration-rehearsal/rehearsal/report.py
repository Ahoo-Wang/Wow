#!/usr/bin/env python3
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""Renders the blue-green rehearsal's steps.jsonl (rehearse.sh) as report.md: steps, durations, volumes, outcomes."""

import json
import re
import sys
from pathlib import Path

work = Path(sys.argv[1])
steps = [json.loads(line) for line in (work / "steps.jsonl").read_text().splitlines() if line.strip()]
by_name = {step["step"]: step for step in steps}


def seconds(name):
    step = by_name.get(name)
    return f"{step['millis'] / 1000:.1f} s" if step else "n/a"


def data(name):
    return (by_name.get(name) or {}).get("data") or {}


def log_lines(name, pattern):
    path = work / f"{name}.log"
    if not path.exists():
        return []
    return [line for line in path.read_text().splitlines() if re.search(pattern, line)]


DONE = re.compile(
    r"^(?P<aggregate>\S+) done: (?P<instances>\d+) instances, (?P<imported>\d+) streams imported, (?P<skipped>\d+) "
    r"skipped, (?P<events>\d+) events, .*?(?P<rate>[\d.]+) streams/s in (?P<seconds>[\d.]+) s; worker time: read 9\.x "
    r"(?P<read>[\d.]+) s, convert (?P<convert>[\d.]+) s, append (?P<append>[\d.]+) s, checkpoints (?P<checkpoints>[\d.]+) s$"
)

PASSES = re.compile(
    r"^\S+ (compared again: .*|compared: .*|is compared in full again: .*|snapshots caught up: .*|snapshots done: .*)$"
)


def container(name):
    millis = data(name).get("containerMillis")
    return millis / 1000 if millis is not None else None


def jvms():
    """The full version of each JVM the run ran on (rehearse.sh jvms_json), one bullet each."""
    names = {"ninex": "9.x stand-in", "v10": "V10 hosts, migrate-data's installation and the driver"}
    parts = data("jvms").items()
    return [f"- JVM, {names.get(part, f'the migrate-data image {part}')}: {version.strip()}" for part, version in parts]


def slow_queries(name):
    """The slow queries in the MongoDB log of step NAME (kind.sh mongo_log): how many, and their milliseconds."""
    path = work / "cluster" / f"mongo-{name}.log"
    count, millis = 0, 0
    if path.exists():
        for line in path.read_text().splitlines():
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            if entry.get("msg") == "Slow query":
                count += 1
                millis += entry.get("attr", {}).get("durationMillis", 0)
    return count, millis


if any(step["step"].startswith("cut-") for step in steps):
    # MODE=cutover: the write stop of an online cut-over by two images, A B B A, each after its import and dual run.
    seed = data("seed")
    seeded = data("ninex-seeded")
    runs = [step["step"][: -len("-import")] for step in steps if re.fullmatch(r"cut-\d+-[AB]-import", step["step"])]
    lines = [
        "# migrate-data cut-over benchmark",
        "",
        f"- 9.x seeded: {seed.get('orders', '?')} orders, {seed.get('carts', '?')} carts; {seeded.get('orders', '?')} "
        f"order streams, {seeded.get('carts', '?')} cart streams, {seeded.get('views', '?')} view streams.",
        "- A is the baseline image, B the candidate, A B B A (D392 ③), each into a fresh database after its own import "
        "and dual run, in which the driver writes to 9.x while the tail follows it. B walks compare and snapshot once in "
        "the dual run; A, as main's run book has it, only in the write stop. The write stop is the final pass, compare "
        "and snapshot, by their containers' time; the activation is not part of it.",
        *jvms(),
        "",
        "| Run | Orders touched | Import | Dual-run compare | Dual-run snapshot | Final pass | Compare | Snapshot "
        "| Write stop |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]

    def cell(value):
        return f"{value:.1f} s" if value is not None else "-"

    for run in runs:
        window = [container(f"{run}-{part}") for part in ("final", "compare", "snapshot")]
        lines.append(
            f"| {run} | {data(f'{run}-traffic').get('touched', '?')} | {cell(container(f'{run}-import'))} | "
            f"{cell(container(f'{run}-dual-compare'))} | {cell(container(f'{run}-dual-snapshot'))} | "
            + " | ".join(cell(value) for value in window)
            + f" | {cell(sum(window) if None not in window else None)} |"
        )
    lines += ["", "What the write stop's compare and snapshot read:", ""]
    for run in runs:
        for part in ("compare", "snapshot"):
            lines += [f"- {run}-{part}: {line}" for line in log_lines(f"{run}-{part}", PASSES.pattern)]
    lines += ["", "| Step | Slow queries | Their time |", "| --- | --- | --- |"]
    for step in steps:
        count, millis = slow_queries(step["step"])
        if step["step"].startswith("cut-") and (work / "cluster" / f"mongo-{step['step']}.log").exists():
            lines.append(f"| {step['step']} | {count} | {millis / 1000:.1f} s |")
    print("\n".join(lines))
    sys.exit(0)

if any(step["step"].startswith("bench-") for step in steps):
    # MODE=bench: the import of two images, A B B A; nothing else ran after the check.
    seed = data("seed")
    seeded = data("ninex-seeded")
    lines = [
        "# migrate-data import benchmark",
        "",
        f"- 9.x seeded: {seed.get('orders', '?')} orders, {seed.get('carts', '?')} carts; {seeded.get('orders', '?')} "
        f"order streams, {seeded.get('carts', '?')} cart streams, {seeded.get('views', '?')} view streams.",
        "- A is the baseline image, B the candidate, each a Job into a fresh database, A B B A (D392 ③): each "
        "image's two runs are its noise band. The step is what an operator waits for; the container time is the tool's "
        "own run. Worker time is summed over the instances imported at once.",
        *jvms(),
        "",
        "| Run | Image | Step | Container |",
        "| --- | --- | --- | --- |",
    ]
    bench = [step for step in steps if step["step"].startswith(("bench-", "sweep-", "groups-"))]
    for step in bench:
        container = step["data"].get("containerMillis")
        lines.append(
            f"| {step['step']} | {step['step'][-1]} | {step['millis'] / 1000:.1f} s | "
            + (f"{container / 1000:.1f} s |" if container is not None else "n/a |")
        )
    lines += [
        "",
        "| Run | Aggregate | Streams | Time | Streams/s | Read 9.x | Convert | Append | Checkpoints |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for step in bench:
        for line in log_lines(step["step"], DONE.pattern):
            done = DONE.match(line)
            streams = int(done["imported"]) + int(done["skipped"])
            lines.append(
                f"| {step['step']} | {done['aggregate']} | {streams} | {done['seconds']} s | {done['rate']} | "
                f"{done['read']} s | {done['convert']} s | {done['append']} s | {done['checkpoints']} s |"
            )
    loads = [step for step in steps if step["step"].startswith("load-")]
    if loads:
        lines += [
            "",
            "9.x's MongoDB while each ran (kind.sh mongo_load_start): mongod's CPU, its operations per second, and a "
            "probe reading one order's streams by id every 200 ms, as 9.x serves a command. `idle` is the store with "
            "no import; `sweep-N` is the candidate walking N ranges of ids at once (readParallelism), and `groups-N` "
            "2 ranges with N groups appended at once (concurrency) and a pool of 2N connections.",
            "",
            "| Run | Import | mongod CPU (cores) | Queries/s | getMores/s | Probe p50 | Probe p99 | Probe max |",
            "| --- | --- | --- | --- | --- | --- | --- | --- |",
        ]
        for load in loads:
            run, load_data = load["step"][len("load-"):], load["data"]
            ops, probe = load_data.get("opsPerSecond", {}), load_data.get("probe", {})
            imported = by_name.get(run)
            lines.append(
                f"| {run} | " + (f"{imported['millis'] / 1000:.1f} s" if imported else "-")
                + f" | {load_data.get('cpuCores')} | {ops.get('query')} | {ops.get('getmore')} | "
                f"{probe.get('p50')} ms | {probe.get('p99')} ms | {probe.get('max')} ms |"
            )
    print("\n".join(lines))
    sys.exit(0)

lines = ["# Blue-green rehearsal (roadmap 8c.1)", "", *jvms(), *([""] if data("jvms") else [])]
seed = data("seed")
seeded = data("ninex-seeded")
lines += [
    "## Data",
    "",
    f"- 9.x seeded: {seed.get('orders', '?')} orders over {seed.get('tenants', '?')} tenants, "
    f"{seed.get('carts', '?')} carts; {seed.get('commands', '?')} commands in {seed.get('seconds', '?')} s "
    f"({seed.get('perSecond', '?')}/s, {seed.get('failed', '?')} failed).",
    f"- 9.x streams: {seeded.get('orders', '?')} order streams, {seeded.get('carts', '?')} cart streams, "
    f"{seeded.get('views', '?')} view streams, {seeded.get('preferences', '?')} preferences streams, "
    f"{seeded.get('wordings', '?')} wording streams.",
    f"- 9.x compensation: {data('seed-compensation').get('executions', '?')} unresolved CartSaga executions.",
    "",
    "## Steps",
    "",
    "| Step | Duration | Exit | Numbers |",
    "| --- | --- | --- | --- |",
]
for step in steps:
    numbers = ", ".join(f"{key} {value}" for key, value in step["data"].items() if key not in ("command", "errors"))
    lines.append(f"| {step['step']} | {step['millis'] / 1000:.1f} s | {step['exit']} | {numbers} |")

lines += [
    "",
    "## Offline cut-over (the default, D267 ⑳)",
    "",
    f"- Write stop (9.x stopped to V10 serving): {seconds('offline-write-stop')}: import {seconds('offline-import')}, "
    f"compare {seconds('offline-compare')}, snapshot {seconds('offline-snapshot')}, "
    f"V10 hosts started {seconds('offline-start')}.",
    f"- Compare: " + "; ".join(log_lines("offline-compare", r"compared:")),
    f"- V10 states vs 9.x before it stopped: orders {data('offline-states-compare')}, "
    f"views {data('offline-views-compare')}.",
]

lag = data("dual-run-lag")
lines += [
    "",
    "## Online cut-over (dual-run, standby, final pass)",
    "",
    f"- Dual-run: 9.x took {data('dual-run-traffic').get('commands', '?')} writes in "
    f"{data('dual-run-traffic').get('seconds', '?')} s while the tail followed; caught up "
    + (f"{lag['caughtUpMillis']} ms after the writes stopped" if "caughtUpMillis" in lag else f"in one pass of {seconds('dual-run-lag')}")
    + ".",
    f"- Write stop (9.x stopped to V10 serving): {seconds('write-stop')}: final import {seconds('final-import')}, "
    f"compare {seconds('compare')}, snapshot {seconds('snapshot')}.",
    f"- Compare: " + "; ".join(log_lines("compare", r"compared:|Comparison done")[:4]),
    "",
    "## Rollbacks",
    "",
    f"- Before V10 writes: export --dry-run: " + "; ".join(log_lines("rollback-before-dry-run", r"not in 9\.x|Dry run")[:2])
    + f"; 9.x states vs V10: {data('rollback-before-compare')}.",
    f"- After V10 writes ({data('v10-traffic').get('commands', '?')} V10 commands): rollback took {seconds('rollback-after')}; "
    + "; ".join(log_lines("export", r" done: |^EQUAL|^DIFFERS"))
    + f"; 9.x states vs V10: {data('rollback-after-compare')}; 9.x then took {data('ninex-after-rollback-traffic').get('commands', '?')} writes.",
    f"- Refusal: after V10 created orders and views, export --dry-run exited "
    f"{by_name.get('export-refused', {}).get('exit', '?')}: "
    + "; ".join(log_lines("export-refused", r"REFUSED|CONFLICT")[:3]),
]

if "timing-job-1" in by_name:
    def run(name, where):
        step = by_name[name]
        container = step["data"].get("containerMillis")
        inside = f"{container / 1000:.1f} s" if container is not None else "n/a"
        return f"| {where} | {name} | {step['millis'] / 1000:.1f} s | {inside} |"

    lines += [
        "",
        "## Where migrate-data runs (kind arm)",
        "",
        "The same full import of the 9.x store, 9.x stopped, each into a fresh database, by the same image and heap "
        "(D392 ③: A B B A, so the two runs of one place are the noise band; single runs are reported, not judged). "
        "The step is what an operator waits for, the Job's scheduling and the init containers' copies included; the "
        "container time is the tool's own run.",
        "",
        "| Where | Step | Step time | Container time |",
        "| --- | --- | --- | --- |",
        run("timing-job-1", "In the cluster (Job)"),
        run("timing-runner-1", "On the runner, through the NodePorts"),
        run("timing-runner-2", "On the runner, through the NodePorts"),
        run("timing-job-2", "In the cluster (Job)"),
        run("timing-port-forward", "On the runner, through kubectl port-forward"),
    ]

def walk(name, since):
    aggregates = data(name).get("aggregates", "?")
    return f"{seconds(name)} from {since}, {aggregates} orders stamped"


def access(name):
    read = data(name)
    return (
        f"a caller of region {read.get('region', '?')} of tenant {read.get('tenant', '?')} read {read.get('visible', '?')} "
        f"of {read.get('sampled', '?')} sampled orders ({read.get('streams', '?')} streams), "
        f"{read.get('wrongStates', '?')} wrong state reads, {read.get('wrongStreams', '?')} wrong stream counts, "
        f"{read.get('leaked', '?')} of {read.get('listed', '?')} listed streams of another region"
    )


def differs(name, pattern):
    return sum(1 for line in log_lines(name, r"^DIFFERS ") if re.search(pattern, line))


ACCESS = r"\$access\."
APPLIED = r"\$access\.(region|teams)"
TAG_LINES = "; ".join(log_lines("check", r"9\.x tag key")[:4]) or "no tag warning"
lines += [
    "",
    "## Access attributes (D390 F)",
    "",
    f"- 9.x tags: {seed.get('tags', '?')} tags streams in the seed (a region, teams; some tagged again or cleared); "
    f"the order's tag keys mapped to region, teams, and the derived country and province.",
    f"- Check: {TAG_LINES}",
    f"- Compare: offline {differs('offline-compare', ACCESS)} instances with an $access difference "
    f"({differs('offline-compare', APPLIED)} on an applied attribute), online "
    f"{differs('compare', ACCESS)} ({differs('compare', APPLIED)} applied).",
    f"- Access walk: offline {walk('offline-access-walk', 'the start')}; online {walk('access-walk', 'the activation')}.",
    f"- Under the policy, offline: {access('offline-access')}.",
    f"- Under the policy, online: {access('access')}.",
]

views = data("seed-views")
lines += [
    "",
    "## View store",
    "",
    f"- 9.x seeded: {views.get('views', '?')} views ({views.get('shared', '?')} shared, {views.get('deleted', '?')} "
    f"deleted, the others personal, some claimed from shared), {views.get('preferences', '?')} users' preferences, "
    f"{views.get('wordings', '?')} dataset wordings; {views.get('commands', '?')} commands, {views.get('failed', '?')} failed.",
    f"- Dual-run: 9.x took {data('dual-run-views').get('commands', '?')} view renames beside the orders; the standby "
    f"V10 view store read them {data('dual-run-views-lag').get('caughtUpMillis', '?')} ms after the writes stopped.",
    f"- Compare: " + "; ".join(log_lines("compare", r"^view-store\.[a-z_]+ compared:")),
    f"- Before V10 writes: 9.x views vs V10: {data('rollback-before-views-compare')}.",
    f"- After V10 writes ({data('v10-views').get('commands', '?')} V10 renames of "
    f"{data('v10-views').get('touched', '?')} views): "
    + "; ".join(log_lines("export", r"^(EQUAL|DIFFERS) view-store"))
    + f"; 9.x renamed each on the version V10 left: {data('rollback-after-views')}.",
    f"- Preferences and wordings: V10 set each on the version after the imported one: {data('v10-settings')}; "
    f"after the rollback, 9.x on the version after V10's: {data('rollback-after-settings')}.",
]
print("\n".join(lines))
