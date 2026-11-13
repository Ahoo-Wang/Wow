# Each measurement of a run with the host's load beside it: a sweep's summary.md (its steps, the other processes' CPU
# and the load average during each, and whether another process disturbed it), and the run's SUMMARY.md on stdout
# (every measurement's peak or rate, and the host's load before and after it).
#   python3 -I summarize.py <raw output root> [--disturbed-cores=2] [--json=<file>]
#   python3 -I summarize.py --peak [--next] --ports=<n> <sweep directory>...
#   python3 -I summarize.py --check <measurement directory>
#
# --peak takes a sweep and the steps measured past it, in order, and says where the throughput stops rising: the
# highest step's gain over the best step below it within the noise band (the difference between two runs of one
# concurrency that failed 1% or less, BAND_MIN at least), a fall, or a step that failed more than 1% of its commands
# (the load generator ends a sweep there; its reason then says whether the throughput was still rising). A peak still
# rising at the last step that fits the client's <n> ephemeral ports (PORT_HEADROOM of them kept free for the
# connections still closing) is a lower bound. With --next it prints the next concurrency to measure, or nothing.
#
# A step is disturbed when the processes that are not the benchmark's used DISTURBED_CORES or more on average over its
# window (from the end of the step before, so its warm-up counts), or a container that is not the benchmark's used
# half a core or more there. A step is void when a JVM that is neither the benchmark's nor allowed (ALLOW_JVM_PIDS in
# lib.sh) ran in its window, when an allowed one used VOID_JVM_CORES or more in two samples in a row there, or when an
# allowed Gradle daemon's log records a build request there: a build holds the CPU for long. One sample over it is a
# spike, recorded and not void. --check prints the void and disturbed steps of a measurement and fails if there are
# any; it first writes allowed-jvms.txt beside them: each spike of an allowed JVM with what its daemon log has in the
# sample before it, and the commands its daemon received. A step is generator-bound when the load generator used GENERATOR_SHARE or more of the
# processors it had, or its busiest thread GENERATOR_THREAD cores or more on average (its HTTP client has one selector
# thread). A sweep's peak is its best step that failed FAILED_SHARE of its commands or less: a step that failed more
# is listed apart, with its failures, and is not a peak. A peak on the sweep's last step, or on a generator-bound step,
# is a lower bound, written ≥; a failure never makes one. A sweep's
# sustainable throughput is that of its highest step with a p99 of SUSTAINABLE_P99_MS or less and no error; it is not
# the peak. A fixed-rate run reached its rate when it answered REACHED_SHARE of it or more with no failure.
#
# --json also writes what SUMMARY.md says as data, for the documentation's figures (the headline writer,
# .github/scripts/bench-headline.mjs): each measurement's peak, sustainable step, every step, and its disturbed and
# void steps, from the same values.
import json
import os
import re
import sys

# The host-load helpers are hostload.py's, beside this file (python3 -I does not put a script's directory on the path).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hostload  # noqa: E402
from hostload import (  # noqa: E402,F401  (window.py reads these as summarize's)
    VOID_JVM_CORES, container_cores, fmt_window, host_lines, load, reasons, record_allowed, samples, stamped,
    step_windows, window)

GENERATOR_SHARE = 0.8
GENERATOR_THREAD = 0.9
SUSTAINABLE_P99_MS = 200
BAND_MIN = 0.03
PORT_HEADROOM = 4096
STEP_GROWTH = 4096
REACHED_SHARE = 0.98
FAILED_SHARE = 0.01
# An error's kind as the load generator counts it, http-<status>-<code>.
KIND = re.compile(r"^http-(\d+)-")
STATUS = r"\1 "


def errors_text(errors):
    return ", ".join(f"{count:,} × {kind}" for kind, count in sorted(errors.items(), key=lambda item: -item[1])) or "0"


def sustainable(steps):
    """The highest step with a p99 of SUSTAINABLE_P99_MS or less and no error; None when there is none."""
    fit = [step for step in steps if step["p99Ms"] <= SUSTAINABLE_P99_MS and not sum(step["errors"].values())]
    return max(fit, key=lambda step: step["concurrency"]) if fit else None


def bound_of(directory, result):
    """Whether a step of the sweep in [directory] was generator-bound, by its cores and its busiest thread."""
    rows = samples(directory)
    windows = step_windows(directory)
    processors = result.get("availableProcessors") or 0

    def generator_bound(step):
        if processors and step.get("generatorCores", 0) >= GENERATOR_SHARE * processors:
            return True
        span = windows.get(step["concurrency"])
        w = window(rows, *span) if span else None
        return bool(w) and w["thread"] is not None and w["thread"] >= GENERATOR_THREAD

    return generator_bound


def failed_share(step):
    errors = sum(step["errors"].values())
    return errors / (errors + step["ok"]) if errors + step["ok"] else 0.0


def clean(step):
    """Whether [step] failed 1% of its commands or less: only such a step can be a peak."""
    return failed_share(step) <= FAILED_SHARE


def failures(step):
    """The share of [step]'s commands that failed, and of what."""
    kinds = sorted(step["errors"].items(), key=lambda item: -item[1])
    return f"{failed_share(step):.1%} (" + ", ".join(f"{count:,} × {KIND.sub(STATUS, kind)}" for kind, count in kinds) + ")"


def failed_line(step, label=None):
    where = f" ({label})" if label else ""
    return (f"{step['concurrency']:,} clients{where}: {step['throughput']:,.1f}/s answered, "
            f"failed {failures(step)}")


def peak_limit(directories, ports, next_step):
    """Where the steps of [directories], in order, stop rising (see the header); markdown, or the next step."""
    measured = []
    for directory in directories:
        result = load(os.path.join(directory, "saturation.json"))
        if result is None:
            continue
        bound = bound_of(directory, result)
        measured += [(os.path.basename(directory), step, bound(step)) for step in result.get("steps", [])]
    kept = [item for item in measured if clean(item[1])]
    if not kept:
        return "" if next_step else "No step failed 1% of its commands or less.\n"
    by_concurrency = {}
    for _, step, _ in kept:
        by_concurrency.setdefault(step["concurrency"], []).append(step["throughput"])
    repeats = [abs(a - b) / max(a, b) for runs in by_concurrency.values() for a, b in zip(runs, runs[1:]) if max(a, b)]
    band = max([BAND_MIN] + repeats)
    _, last, _ = measured[-1]
    # The last step that failed 1% or less, and the best of those below its concurrency.
    _, top, _ = kept[-1]
    below = [step["throughput"] for _, step, _ in kept if step["concurrency"] < top["concurrency"]]
    prior = max(below) if below else 0.0
    rising = bool(prior) and top["throughput"] > prior * (1 + band)
    _, best, best_bound = max(kept, key=lambda item: item[1]["throughput"])
    candidate = top["concurrency"] + STEP_GROWTH
    lower = False
    if not clean(last):
        reason = (f"the step at {last['concurrency']:,} clients failed {failures(last)}, which ends a sweep; a step that "
                  f"fails more than {FAILED_SHARE:.0%} is not a peak")
        if rising:
            reason += (f"; the throughput was still rising when the failures began ({top['concurrency']:,} clients "
                       f"passed the {prior:,.1f}/s below them by more than the band)")
    elif rising:
        if candidate + PORT_HEADROOM <= ports:
            if next_step:
                return f"{candidate}\n"
            reason, lower = f"still rising at {top['concurrency']:,} clients; not measured further", True
        else:
            reason, lower = (f"still rising at {top['concurrency']:,} clients, past the {prior:,.1f}/s below it by more "
                             f"than the band; {candidate:,} clients would not fit the client's {ports:,} ephemeral "
                             f"ports to the server's one port with {PORT_HEADROOM:,} kept for the connections still "
                             f"closing"), True
    else:
        gain = top["throughput"] / prior - 1 if prior else 0.0
        reason = (f"{top['concurrency']:,} clients answered {top['throughput']:,.1f}/s, {gain:+.1%} on the best step "
                  f"below it, within the band or a fall")
    if next_step:
        return ""
    if best_bound:
        lower = True
        reason += f"; the peak's step at {best['concurrency']:,} clients was generator-bound"
    fit = [step for _, step, _ in measured if step["p99Ms"] <= SUSTAINABLE_P99_MS and not sum(step["errors"].values())]
    fit = max(fit, key=lambda step: step["concurrency"]) if fit else None
    failed = [failed_line(step, label) for label, step, _ in measured if not clean(step)]
    lines = [
        f"Peak {'≥ ' if lower else ''}{best['throughput']:,.1f}/s at {best['concurrency']:,} clients: {reason}.",
        f"Noise band {band:.1%} (the larger of {BAND_MIN:.0%} and the difference between two runs of one concurrency "
        f"that failed {FAILED_SHARE:.0%} or less).",
        "Sustainable (the highest step with a p99 of "
        f"{SUSTAINABLE_P99_MS} ms or less and no error): "
        + (f"{fit['throughput']:,.1f}/s at {fit['concurrency']:,} clients, p99 {fit['p99Ms']:.1f} ms." if fit else "none."),
        f"Failed more than {FAILED_SHARE:.0%} (not a peak): " + ("; ".join(failed) if failed else "none") + ".",
        "",
        "| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |",
        "| --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for label, step, bounded in measured:
        lines.append(f"| {label} | {step['concurrency']:,} | {step['throughput']:,.1f} | {step['p99Ms']:.1f} | "
                     f"{failed_share(step):.2%} | {'yes' if bounded else ''} |")
    return "\n".join(lines) + "\n"


def sweep(directory, label):
    result = load(os.path.join(directory, "saturation.json"))
    if result is None:
        return None
    rows = samples(directory)
    windows = step_windows(directory)
    steps = result.get("steps", [])
    kept = [step for step in steps if clean(step)]
    best = max(kept, key=lambda step: step["throughput"]) if kept else None
    processors = result.get("availableProcessors") or 0
    generator_bound = bound_of(directory, result)

    bounded = [step["concurrency"] for step in steps if generator_bound(step)]
    # One step at a fixed concurrency is a result, not a peak.
    single = len(steps) == 1
    lower_bound = best is not None and not single and (best is steps[-1] or generator_bound(best))
    meta = result.get("meta", {})
    lines = [
        f"# {label}: {result.get('scenario')} ({result.get('keys', {}).get('distribution')}), "
        f"{meta.get('nodes', '?')} node(s) on {meta.get('backend', '?')}",
        "",
    ]
    if best:
        bound = "≥ " if lower_bound else ""
        note = ""
        if lower_bound:
            note = " (generator-bound: a lower bound)" if generator_bound(best) else " (its last step: a lower bound)"
        lines.append(f"{'Result' if single else 'Peak'} {bound}{best['throughput']:,.1f}/s at {best['concurrency']:,} "
                     f"clients{note}.")
    fit = sustainable(steps)
    lines.append(
        f"Sustainable (the highest step with a p99 of {SUSTAINABLE_P99_MS} ms or less and no error): "
        + (f"{fit['throughput']:,.1f}/s at {fit['concurrency']:,} clients, p99 {fit['p99Ms']:.1f} ms." if fit else "none."))
    failed = [step for step in steps if not clean(step)]
    note = ""
    if failed and kept and kept[-1] is best:
        below = [step["throughput"] for step in kept if step["concurrency"] < best["concurrency"]]
        if below and best["throughput"] > max(below) * (1 + BAND_MIN):
            note = " The throughput was still rising when the failures began."
    lines.append(f"Failed more than {FAILED_SHARE:.0%} (not a peak): "
                 + ("; ".join(failed_line(step) for step in failed) if failed else "none") + "." + note)
    lines += [
        "",
        f"Generator cores are the load generator's, of the {processors} processors it had; Generator thread is its "
        f"busiest thread's, in cores. A step is generator-bound at {GENERATOR_SHARE:.0%} of the processors or "
        f"{GENERATOR_THREAD:g} of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, "
        f"mean / max over the step's window; a step is disturbed at {hostload.DISTURBED_CORES:g} cores on average or a "
        f"foreign container at {hostload.CONTAINER_CORES:g}. Host CPU is every process's, OrbStack's VM among them, mean cores. "
        f"Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).",
        "",
        "| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread "
        "| Other CPU | Host CPU | Containers | Load (1 min, max) | |",
        "| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |",
    ]
    disturbed, void, cores = [], [], {}
    for step in steps:
        span = windows.get(step["concurrency"])
        w = window(rows, *span) if span else None
        cores[step["concurrency"]] = container_cores(rows, *span) if span else {}
        other, host, thread, load1, flag = fmt_window(w)
        if w and w["disturbed"]:
            disturbed.append(step["concurrency"])
        if w and w["void"]:
            void.append(step["concurrency"])
        if generator_bound(step):
            flag = " ".join(filter(None, [flag, "**generator-bound**"]))
        lines.append(
            f"| {step['concurrency']:,} | {step['throughput']:,.1f} | {step['p50Ms']:.2f} | {step['p99Ms']:.2f} | "
            f"{step['ok']:,} | {errors_text(step['errors'])} | {step.get('generatorCores', 0):.2f} | {thread} | "
            f"{other} | {host} | {containers_text(cores[step['concurrency']])} | {load1} | {flag} |")
    hosts = host_lines(directory)
    lines += ["", f"Load averages before: {hosts.get('begin', '–')}; after: {hosts.get('end', '–')}."]
    for concurrency in sorted(set(disturbed + void)):
        w = window(rows, *windows[concurrency])
        lines.append(f"- {concurrency:,} clients: {'; '.join(reasons(w))}; busiest other processes {w['top']}; "
                     f"containers {w['containerNames'] or 'none'}.")
    with open(os.path.join(directory, "summary.md"), "w", encoding="utf-8") as file:
        file.write("\n".join(lines) + "\n")
    peak = "–"
    if best:
        peak = f"{'≥ ' if lower_bound else ''}{best['throughput']:,.1f}/s at {best['concurrency']:,}"
        if single:
            peak += " (one step)"
    last = steps[-1] if steps else None
    fit = sustainable(steps)
    return {
        "data": {
            "kind": "sweep",
            "scenario": result.get("scenario"),
            "keys": result.get("keys", {}).get("distribution"),
            "nodes": meta.get("nodes"),
            "backend": meta.get("backend"),
            "peak": {"throughput": best["throughput"], "clients": best["concurrency"], "lowerBound": lower_bound,
                     "single": single} if best else None,
            "sustainable": {"throughput": fit["throughput"], "clients": fit["concurrency"], "p50Ms": fit["p50Ms"],
                            "p99Ms": fit["p99Ms"]} if fit else None,
            "steps": [{"clients": step["concurrency"], "throughput": step["throughput"], "p50Ms": step["p50Ms"],
                       "p99Ms": step["p99Ms"], "failedShare": failed_share(step), "errors": step["errors"],
                       "containerCores": {name: round(value, 3) for name, value in cores[step["concurrency"]].items()}}
                      for step in steps],
            "disturbed": disturbed,
            "void": void,
            "generatorBound": bounded,
        },
        "peak": peak,
        "sustainable": f"{fit['throughput']:,.1f}/s at {fit['concurrency']:,}" if fit else "none",
        "last": f"{last['throughput']:,.1f}/s at {last['concurrency']:,}, errors {errors_text(last['errors'])}" if last else "–",
        "disturbed": ", ".join(f"{c:,}" for c in disturbed) or "none",
        "void": ", ".join(f"{c:,}" for c in void) or "none",
        "failed": "; ".join(f"{step['concurrency']:,}: {failures(step)}" for step in failed) or "none",
        "generator": ", ".join(f"{c:,}" for c in bounded) or "none",
        "before": hosts.get("begin", "–"),
        "after": hosts.get("end", "–"),
    }


def containers_text(cores):
    """Each container's mean cores, the busiest first; – without a sample."""
    return ", ".join(f"{name} {value:.2f}" for name, value in sorted(cores.items(), key=lambda item: -item[1])) or "–"


def fixed(directory):
    result = load(os.path.join(directory, "result.json"))
    if result is None:
        return None
    r = result["results"]
    started = result.get("startedAt")
    rows = samples(directory)
    lines = stamped(directory, "run.log")
    w = window(rows, int(lines[0].split(" ", 1)[0]), int(lines[-1].split(" ", 1)[0])) if lines else None
    hosts = host_lines(directory)
    environment = result.get("environment", {})
    rate = result["load"]["rate"]
    reached = r["throughput"] >= REACHED_SHARE * rate and r["failed"] == 0
    return {
        "data": {
            "kind": "fixed",
            "scenario": result.get("scenario"),
            "nodes": result.get("meta", {}).get("nodes"),
            "backend": result.get("meta", {}).get("backend"),
            "rate": rate,
            "throughput": r["throughput"],
            "p50Ms": r["latencyMs"]["p50"],
            "p99Ms": r["latencyMs"]["p99"],
            "failed": r["failed"],
            "reached": reached,
            "commit": environment.get("commit"),
            "disturbed": bool(w and w["disturbed"]),
            "void": bool(w and w["void"]),
        },
        "peak": f"{r['throughput']:,.1f}/s at {rate:,.0f}/s ({'reached' if reached else '**not reached**'}), "
                f"p50 {r['latencyMs']['p50']:.2f} ms, p99 {r['latencyMs']['p99']:.1f} ms, failed {r['failed']}",
        "last": f"commit {environment.get('commit', '?')[:10]}, dirty {environment.get('dirty')}, started {started}",
        "disturbed": "yes" if w and w["disturbed"] else "none",
        "void": "yes" if w and w["void"] else "none",
        "failed": "–",
        "generator": "–",
        "sustainable": "–",
        "before": hosts.get("begin", "–"),
        "after": hosts.get("end", "–"),
    }


def check(directory):
    """The void and disturbed steps of the measurement in [directory], with why."""
    record_allowed(directory)
    rows = samples(directory)
    found = []
    result = load(os.path.join(directory, "saturation.json"))
    if result is not None:
        windows = step_windows(directory)
        for step in result.get("steps", []):
            span = windows.get(step["concurrency"])
            w = window(rows, *span) if span else None
            if w and (w["void"] or w["disturbed"]):
                found.append(f"{step['concurrency']:,} clients: {'; '.join(reasons(w))}")
    elif load(os.path.join(directory, "result.json")) is not None:
        lines = stamped(directory, "run.log")
        w = window(rows, int(lines[0].split(" ", 1)[0]), int(lines[-1].split(" ", 1)[0])) if lines else None
        if w and (w["void"] or w["disturbed"]):
            found.append(f"the run: {'; '.join(reasons(w))}")
    return found


def main(root, json_path=None):
    print(f"# {os.path.basename(os.path.abspath(root))}: measurements\n")
    try:
        with open(os.path.join(root, "environment.txt"), encoding="utf-8") as file:
            print("```text\n" + file.read().strip() + "\n```\n")
    except OSError:
        pass
    print(f"| Measurement | Peak (failed {FAILED_SHARE:.0%} or less) or result | Sustainable | Failed more than "
          f"{FAILED_SHARE:.0%} | Last step or provenance | Disturbed steps | Void steps | Generator-bound steps "
          "| Load before | Load after |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    allowed, jvms, measurements, data = [], set(), 0, {}
    for path, directories, files in os.walk(root):
        # The sessions a rerun replaced are not the run's.
        directories[:] = sorted(name for name in directories if not (path == root and name == "replaced"))
        label = os.path.relpath(path, root)
        row = None
        if "saturation.json" in files:
            row = sweep(path, label)
        elif "result.json" in files:
            row = fixed(path)
        if row:
            data[label] = dict(row["data"], setAside=".void-" in label)
            if ".void-" in label:
                row["void"] += " (an attempt set aside: void by the rule in force when it was measured, the script's log)"
            print(f"| {label} | {row['peak']} | {row['sustainable']} | {row['failed']} | {row['last']} "
                  f"| {row['disturbed']} | {row['void']} | {row['generator']} | {row['before']} | {row['after']} |")
            measurements += 1
            for sample in samples(path):
                if sample[8] is not None:
                    allowed.append(sample[8])
                jvms.update(pid for pid in sample[9].split(",") if pid)
    if allowed:
        print(f"\nThe allowed JVMs (environment.txt) over {measurements} measurements, {len(allowed):,} samples: "
              f"mean {sum(allowed) / len(allowed):.1%}, max {max(allowed):.1%} of one core; a step is void with two "
              f"samples in a row at {VOID_JVM_CORES:.0%} or more, or a build request in a daemon's log "
              "(allowed-jvms.txt).")
    print(f"\nOther JVMs seen during the measurements: {', '.join(sorted(jvms)) or 'none'}.")
    if json_path:
        with open(json_path, "w", encoding="utf-8") as file:
            json.dump({"schema": "wow-m5max-snapshot/summary/1", "sustainableP99Ms": SUSTAINABLE_P99_MS,
                       "failedShare": FAILED_SHARE, "measurements": data}, file, indent=2, sort_keys=True)
            file.write("\n")


if __name__ == "__main__":
    arguments = [argument for argument in sys.argv[1:] if not argument.startswith("--")]
    flags = [argument for argument in sys.argv[1:] if argument.startswith("--")]
    for argument in flags:
        if argument.startswith("--disturbed-cores="):
            hostload.DISTURBED_CORES = float(argument.split("=", 1)[1])
    if "--check" in flags:
        if len(arguments) != 1:
            sys.exit("usage: summarize.py --check <measurement directory>")
        found = check(arguments[0])
        if found:
            print("void or disturbed: " + " | ".join(found))
            sys.exit(1)
    elif "--peak" in flags:
        ports = [int(flag.split("=", 1)[1]) for flag in flags if flag.startswith("--ports=")]
        if not arguments or not ports:
            sys.exit("usage: summarize.py --peak [--next] --ports=<n> <sweep directory>...")
        sys.stdout.write(peak_limit(arguments, ports[0], "--next" in flags))
    elif len(arguments) != 1:
        sys.exit("usage: summarize.py <raw output root> [--disturbed-cores=2]")
    else:
        json_paths = [flag.split("=", 1)[1] for flag in flags if flag.startswith("--json=")]
        main(arguments[0], json_paths[0] if json_paths else None)
