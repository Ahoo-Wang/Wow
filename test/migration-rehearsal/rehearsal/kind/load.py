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
"""9.x's MongoDB load while one step ran (kind.sh mongo_load_start and mongo_load_stop), as a JSON object.

Arguments: the work directory, the run's name and the pod's clock ticks per second. The run's files hold mongod's CPU
time in ticks, its operation counters and the times, each at the start and the end, and the probe's reads in
milliseconds, one a line.
"""

import json
import sys
from pathlib import Path

work, name, ticks = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])


def lines(suffix):
    return (work / f"load-{name}.{suffix}").read_text().split("\n")


cpu = [float(line) for line in lines("cpu") if line.strip()]
at = [int(line) for line in lines("at") if line.strip()]
ops = [json.loads(line) for line in lines("ops") if line.startswith("{")]
seconds = (at[1] - at[0]) / 1000
reads = []
for line in lines("probe"):
    try:
        reads.append(float(line))
    except ValueError:
        pass
reads.sort()


def percentile(share):
    return round(reads[min(len(reads) - 1, int(share * len(reads)))], 3) if reads else None


print(json.dumps({
    "seconds": round(seconds, 1),
    "cpuCores": round((cpu[1] - cpu[0]) / ticks / seconds, 2),
    "opsPerSecond": {key: round((ops[1][key] - ops[0][key]) / seconds) for key in ops[0]},
    "probe": {"reads": len(reads), "p50": percentile(0.5), "p99": percentile(0.99), "max": reads[-1] if reads else None},
}))
