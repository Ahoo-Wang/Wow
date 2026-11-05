# The host around each of the run's measurements: for every bench.sh run of the suites (a directory with a
# saturation.json or a result.json), the samples of the run's host-load.tsv from the directory's creation to that
# file's last write, judged by the snapshot's rules (summarize.py, D392 ⑧): void when another JVM ran, an allowed one
# used 5% of a core or more in two samples in a row, or an allowed Gradle daemon received a build request; disturbed
# when the other processes used 2 cores or more on average or another container half a core. Markdown on stdout;
# fails if a run is void or disturbed. allowed-jvms.txt (the allowed JVMs' spikes and commands) goes beside the samples.
#   python3 -I window.py <summarize.py> <raw output root>
import importlib.util
import os
import sys


def load(path):
    spec = importlib.util.spec_from_file_location("summarize", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main(summarize, root):
    s = load(summarize)
    s.record_allowed(root)
    rows = s.samples(root)
    found = []
    for path, directories, files in os.walk(root):
        directories.sort()
        name = next((file for file in ("saturation.json", "result.json") if file in files), None)
        if not name:
            continue
        start = int(os.stat(path).st_birthtime)
        end = int(os.stat(os.path.join(path, name)).st_mtime)
        found.append((start, end, os.path.relpath(path, root), s.window(rows, start, end)))
    found.sort()
    print(f"# The host during each measurement of {os.path.basename(os.path.abspath(root))}\n")
    print(f"{len(rows):,} samples. Other CPU and containers in cores, mean / max over the measurement; the allowed "
          "JVMs' CPU at its highest sample.\n")
    print("| Measurement | From | To | Other CPU | Busiest container | Allowed JVMs | Other JVMs | Verdict |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |")
    bad = []
    for start, end, label, w in found:
        if w is None:
            print(f"| {label} | {start} | {end} | – | – | – | – | no sample |")
            continue
        verdict = "; ".join(s.reasons(w)) if (w["void"] or w["disturbed"]) else "ok"
        if w["void"] or w["disturbed"]:
            bad.append(label)
        allowed = f"{w['allowed']:.2f}" if w["allowed"] is not None else "–"
        print(f"| {label} | {start} | {end} | {w['mean']:.2f} / {w['max']:.2f} | {w['containers']:.2f} | {allowed} "
              f"| {', '.join(w['jvms']) or 'none'} | {verdict} |")
    print(f"\n{len(found)} measurements; void or disturbed: {', '.join(bad) or 'none'}.")
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: window.py <summarize.py> <raw output root>")
    sys.exit(main(sys.argv[1], sys.argv[2]))
