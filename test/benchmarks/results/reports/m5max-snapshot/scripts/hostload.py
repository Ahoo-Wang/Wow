# The host around a measurement, as lib.sh's sampler records it in host-load.tsv: the samples, a step's window of
# them, and the rules that make a window disturbed or void (summarize.py's header states them). summarize.py and
# m5max-axon's window.py read these through summarize.py's names.
#
# The sampler also records each of the benchmark's containers' cumulative CPU (cgroup `usage_usec`, last column,
# `name=usec,…`); container_cores gives each one's mean cores over a window from the two samples bracketing it,
# interpolated to the window's edges.
import datetime
import glob
import json
import os
import re

DISTURBED_CORES = 2.0
CONTAINER_CORES = 0.5
VOID_JVM_CORES = 0.05
STEP = re.compile(r"^(\d+) \[saturate\] .*?: concurrency=(\d+) ")
# A Gradle daemon's log line: its time, and the routine lines of an idle daemon.
DAEMON_LINE = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}[+-]\d{4}) ")
DAEMON_ROUTINE = ("periodic daemon health check", "lock on daemon addresses registry", "Lock acquired on daemon addresses",
                  "Releasing lock on daemon addresses")


def load(path):
    try:
        with open(path, encoding="utf-8") as file:
            return json.load(file)
    except (OSError, ValueError):
        return None


class Samples(list):
    """The rows of a measurement's host-load.tsv, with its directory."""

    directory = ""


def samples(directory):
    """(epoch, load1, foreign cores, top foreign, foreign containers' cores, containers, host cores, the generator's
    busiest thread's cores, the allowed JVMs' cores, the other JVMs' pids, the benchmark's containers' cumulative CPU
    {name: usec}) of host-load.tsv."""
    rows = Samples()
    rows.directory = directory
    try:
        with open(os.path.join(directory, "host-load.tsv"), encoding="utf-8") as file:
            next(file)
            for line in file:
                fields = line.rstrip("\n").split("\t")
                if len(fields) < 5:
                    continue
                containers = fields[6] if len(fields) > 6 else ""
                container_cores = 0.0
                for item in filter(None, containers.split(",")):
                    value = item.rsplit(" ", 1)[-1].rstrip("%")
                    try:
                        container_cores = max(container_cores, float(value) / 100)
                    except ValueError:
                        pass
                host = fields[7] if len(fields) > 7 and fields[7] else None
                thread = fields[8] if len(fields) > 8 and fields[8] else None
                allowed = fields[9] if len(fields) > 9 and fields[9] else None
                jvms = fields[10] if len(fields) > 10 else ""
                usage = {}
                for item in filter(None, (fields[11] if len(fields) > 11 else "").split(",")):
                    name, _, usec = item.partition("=")
                    if usec.isdigit():
                        usage[name] = int(usec)
                rows.append((int(fields[0]), float(fields[1]), float(fields[4] or 0) / 100,
                             fields[5] if len(fields) > 5 else "", container_cores, containers,
                             float(host) / 100 if host else None, float(thread) / 100 if thread else None,
                             float(allowed) / 100 if allowed else None, jvms, usage))
    except OSError:
        pass
    return rows


def window(rows, start, end):
    """The other processes' mean and max cores, the max load average, the busiest container's max cores, the
    busiest other processes, the host's mean cores (every process), the generator's busiest thread's mean cores, the
    allowed JVMs' max cores and the other JVMs' pids, over [start, end]; None without a sample."""
    inside = [row for row in rows if start <= row[0] <= end]
    if not inside:
        return None
    over = [i for i, row in enumerate(rows) if row[8] is not None and row[8] >= VOID_JVM_CORES]
    spikes = [rows[i] for i in over if start <= rows[i][0] <= end]
    # Two samples in a row over it, one of them in the window.
    sustained = any(i + 1 in over and (start <= rows[i][0] <= end or start <= rows[i + 1][0] <= end) for i in over)
    busiest = max(inside, key=lambda row: row[2])
    hosts = [row[6] for row in inside if row[6] is not None]
    threads = [row[7] for row in inside if row[7] is not None]
    allowed = [row[8] for row in inside if row[8] is not None]
    jvms = sorted({pid for row in inside for pid in row[9].split(",") if pid})
    builds = [epoch for epoch, command in commands_of(rows.directory) if start <= epoch <= end
              and command.startswith("Build")]
    return {
        "allowed": max(allowed) if allowed else None,
        "spikes": [(row[0], row[8]) for row in spikes],
        "sustained": sustained,
        "builds": builds,
        "jvms": jvms,
        "void": bool(jvms) or sustained or bool(builds),
        "host": sum(hosts) / len(hosts) if hosts else None,
        "thread": sum(threads) / len(threads) if threads else None,
        "mean": sum(row[2] for row in inside) / len(inside),
        "max": busiest[2],
        "load": max(row[1] for row in inside),
        "containers": max(row[4] for row in inside),
        "top": busiest[3],
        "containerNames": max(inside, key=lambda row: row[4])[5],
        "disturbed": sum(row[2] for row in inside) / len(inside) >= DISTURBED_CORES
        or max(row[4] for row in inside) >= CONTAINER_CORES,
    }


def commands_of(directory):
    """(epoch, command) of the commands the allowed daemons received, from allowed-jvms.txt."""
    found = []
    try:
        with open(os.path.join(directory, "allowed-jvms.txt"), encoding="utf-8") as file:
            for line in file:
                if line.startswith("command "):
                    _, epoch, command = line.rstrip("\n").split(" ", 2)
                    found.append((int(epoch), command))
    except (OSError, ValueError):
        pass
    return found


def daemon_lines(pid):
    """(epoch, line) of the Gradle daemon [pid]'s log, if it has one; and the log's path."""
    home = os.environ.get("GRADLE_USER_HOME") or os.path.expanduser("~/.gradle")
    paths = glob.glob(os.path.join(home, "daemon", "*", f"daemon-{pid}.out.log"))
    if not paths:
        return [], None
    found = []
    with open(paths[0], encoding="utf-8", errors="replace") as file:
        for line in file:
            match = DAEMON_LINE.match(line)
            if match:
                stamp = datetime.datetime.strptime(match.group(1), "%Y-%m-%dT%H:%M:%S.%f%z")
                found.append((int(stamp.timestamp()), line.rstrip("\n")))
    return found, paths[0]


def record_allowed(directory):
    """Writes [directory]/allowed-jvms.txt (see the header) from the daemon logs of ALLOW_JVM_PIDS."""
    pids = os.environ.get("ALLOW_JVM_PIDS", "").split()
    rows = samples(directory)
    if not pids or not rows:
        return
    out = ["# The JVMs allowed to run (ALLOW_JVM_PIDS) over this measurement: each sample at "
           f"{VOID_JVM_CORES:.0%} of a core or more (their CPU is summed), the lines of their daemon logs since the "
           "sample before it (the routine ones counted), and the commands the daemons received (`command <epoch> ...`)."]
    spikes = [(i, row) for i, row in enumerate(rows) if row[8] is not None and row[8] >= VOID_JVM_CORES]
    for pid in pids:
        lines, path = daemon_lines(pid)
        out.append(f"pid {pid}: log {path or 'none'}")
        for epoch, line in lines:
            if rows[0][0] - 10 <= epoch <= rows[-1][0] and "Received command: " in line:
                out.append(f"command {epoch} {line.split('Received command: ', 1)[1][:200]}")
        for i, row in spikes:
            since = rows[i - 1][0] if i else row[0] - 10
            inside = [line for epoch, line in lines if since < epoch <= row[0]]
            routine = [line for line in inside if any(text in line for text in DAEMON_ROUTINE)]
            out.append(f"spike {row[0]} {row[8]:.3f} cores: {len(inside)} log lines since {since}, "
                       f"{len(routine)} of them routine (health checks, registry locks)")
            out += ["  " + line[:240] for line in inside if line not in routine][:20]
    with open(os.path.join(directory, "allowed-jvms.txt"), "w", encoding="utf-8") as file:
        file.write("\n".join(out) + "\n")


def stamped(directory, name):
    try:
        with open(os.path.join(directory, name), encoding="utf-8", errors="replace") as file:
            return [line for line in file if line[:10].isdigit()]
    except OSError:
        return []


def step_windows(directory):
    """{concurrency: (start, end)} of a sweep from its saturate.log."""
    lines = stamped(directory, "saturate.log")
    if not lines:
        return {}
    start = int(lines[0].split(" ", 1)[0])
    found = {}
    for line in lines:
        match = STEP.match(line)
        if match:
            end = int(match.group(1))
            found[int(match.group(2))] = (start, end)
            start = end + 1
    return found


def host_lines(directory):
    """The uptime lines of host.txt, by block title."""
    found = {}
    title = None
    try:
        with open(os.path.join(directory, "host.txt"), encoding="utf-8") as file:
            for line in file:
                if line.startswith("## "):
                    title = line[3:].split(" 20", 1)[0].strip()
                elif line.startswith("uptime: ") and title:
                    found[title] = line.split("load averages:", 1)[-1].strip()
    except OSError:
        pass
    return found


def fmt_window(w):
    if w is None:
        return "–", "–", "–", "–", ""
    flag = " ".join(filter(None, ["**void**" if w["void"] else "", "**disturbed**" if w["disturbed"] else ""]))
    host = f"{w['host']:.1f}" if w["host"] is not None else "–"
    thread = f"{w['thread']:.2f}" if w["thread"] is not None else "–"
    return f"{w['mean']:.2f} / {w['max']:.2f}", host, thread, f"{w['load']:.1f}", flag


def reasons(w):
    """Why the window [w] is void or disturbed."""
    found = []
    if w["jvms"]:
        found.append(f"other JVMs ran (pids {', '.join(w['jvms'])})")
    if w["sustained"]:
        found.append(f"the allowed JVMs used {VOID_JVM_CORES:g} cores or more in two samples in a row")
    if w["builds"]:
        found.append(f"an allowed daemon received a build request at {', '.join(map(str, w['builds']))}")
    if w["mean"] >= DISTURBED_CORES:
        found.append(f"other processes {w['mean']:.2f} cores on average (busiest: {w['top']})")
    if w["containers"] >= CONTAINER_CORES:
        found.append(f"a container at {w['containers']:.2f} cores ({w['containerNames']})")
    return found


def container_cores(rows, start, end):
    """{container: mean cores} of the benchmark's containers over [start, end], from their cumulative CPU at the
    window's edges, each interpolated between the samples around it; a container without a sample on each side of an
    edge is left out. Empty without such samples."""
    def at(name, epoch):
        before = [row for row in rows if row[0] <= epoch and name in row[10]]
        after = [row for row in rows if row[0] >= epoch and name in row[10]]
        if not before or not after:
            return None
        a, b = before[-1], after[0]
        if b[0] == a[0]:
            return a[10][name]
        return a[10][name] + (b[10][name] - a[10][name]) * (epoch - a[0]) / (b[0] - a[0])

    if end <= start:
        return {}
    found = {}
    for name in sorted({name for row in rows for name in row[10]}):
        first, last = at(name, start), at(name, end)
        if first is not None and last is not None and last >= first:
            found[name.removeprefix("wow-e2e-bench-")] = (last - first) / 1e6 / (end - start)
    return found
