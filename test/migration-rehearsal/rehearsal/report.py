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


lines = ["# Blue-green rehearsal (roadmap 8c.1)", ""]
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

lag = data("dual-run-lag")
lines += [
    "",
    "## Cut-over",
    "",
    f"- Dual-run: 9.x took {data('dual-run-traffic').get('commands', '?')} writes in "
    f"{data('dual-run-traffic').get('seconds', '?')} s while the tail followed; caught up "
    + (f"{lag['caughtUpMillis']} ms after the writes stopped" if "caughtUpMillis" in lag else f"in one pass of {seconds('dual-run-lag')}")
    + ".",
    f"- Write stop (9.x stopped to V10 serving): {seconds('write-stop')}: final import {seconds('final-import')}, "
    f"compare {seconds('compare')}, snapshot {seconds('snapshot')}, backfill-tags {seconds('backfill-tags')}.",
    f"- Compare: " + "; ".join(log_lines("compare", r"compared:|Comparison done")[:4]),
    "",
    "## Rollbacks",
    "",
    f"- Before V10 writes: export --dry-run: " + "; ".join(log_lines("rollback-before-dry-run", r"not in 9\.x|Dry run")[:2])
    + f"; 9.x states vs V10: {data('rollback-before-compare')}.",
    f"- After V10 writes ({data('v10-traffic').get('commands', '?')} V10 commands): rollback took {seconds('rollback-after')}; "
    + "; ".join(log_lines("export", r" done: |^EQUAL|^DIFFERS"))
    + f"; 9.x states vs V10: {data('rollback-after-compare')}; 9.x then took {data('ninex-after-rollback-traffic').get('commands', '?')} writes.",
    f"- Refusal: after V10 created orders and saved views, export --dry-run exited "
    f"{by_name.get('export-refused', {}).get('exit', '?')}: "
    + "; ".join(log_lines("export-refused", r"REFUSED|CONFLICT")[:3]),
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
]
print("\n".join(lines))
