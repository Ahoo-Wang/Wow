# The warnings and errors of a session's node logs, without the logs: each message with its numbers and ids masked,
# how many lines each node logged of it and when, and the measurement step each line fell in (the steps from the
# stamped saturate.log and run.log of the session's measurements). Markdown on stdout.
#   python3 -I digest-logs.py <session dir> <node log> [<node log> ...]
import datetime
import os
import re
import sys

LINE = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+[+-]\d\d:\d\d)\s+(WARN|ERROR)\s+.*?\s(\S+)\s+:\s(.*)$")
STAMPED = re.compile(r"^\d{4}-\d\d-\d\dT")
STEP = re.compile(r"^(\d+) \[saturate\] .*?: concurrency=(\d+) ")
MASKS = (
    (re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<id>"),
    # Reactor Netty's channel, as [fc1c4b7d-1, L:/127.0.0.1:18080 - R:/127.0.0.1:60708].
    (re.compile(r"\[[0-9a-f]{8}(?:-\d+)?, L:"), "[<channel>, L:"),
    (re.compile(r"\b[0-9a-f]{16,}\b"), "<id>"),
    (re.compile(r"acc-\d+"), "acc-<n>"),
    (re.compile(r"\b\d+\b"), "<n>"),
)
WIDTH = 220


def mask(text):
    for pattern, replacement in MASKS:
        text = pattern.sub(replacement, text)
    return text if len(text) <= WIDTH else text[:WIDTH] + "…"


def windows(session):
    """(start, end, measurement, step) for each step of each measurement in the session, by its stamped log."""
    found = []
    for name in sorted(os.listdir(session)):
        for log, fixed in (("saturate.log", False), ("run.log", True)):
            path = os.path.join(session, name, log)
            if not os.path.exists(path):
                continue
            with open(path, encoding="utf-8", errors="replace") as file:
                stamped = [line for line in file if line[:10].isdigit()]
            if not stamped:
                continue
            start = int(stamped[0].split(" ", 1)[0])
            end = int(stamped[-1].split(" ", 1)[0])
            if fixed:
                found.append((start, end + 1, name, "fixed rate"))
                continue
            for line in stamped:
                match = STEP.match(line)
                if match:
                    step_end = int(match.group(1))
                    found.append((start, step_end + 1, name, match.group(2)))
                    start = step_end + 1
    return sorted(found)


def window_of(epoch, steps):
    for start, end, name, step in steps:
        if start <= epoch < end:
            return f"{name} {step}"
    return "outside the measurements"


def entries(path):
    """(epoch, level, logger, message, cause, raw) for each warning or error, its cause from the lines after it."""
    with open(path, encoding="utf-8", errors="replace") as file:
        lines = file.read().splitlines()
    for index, line in enumerate(lines):
        match = LINE.match(line)
        if not match:
            continue
        cause = root = ""
        for following in lines[index + 1:index + 200]:
            if STAMPED.match(following):
                break
            text = following.strip()
            if not text or text.startswith("at ") or text.startswith("..."):
                continue
            if text.startswith("Caused by: "):
                root = text[len("Caused by: "):]
            elif not cause:
                cause = text
        epoch = datetime.datetime.fromisoformat(match.group(1)).timestamp()
        yield epoch, match.group(2), match.group(3), match.group(4), root or cause, line


def main(session, logs):
    steps = windows(session)
    messages = {}
    by_step = {}
    nodes = [os.path.basename(log).rsplit(".", 1)[0] for log in logs]
    for node, log in zip(nodes, logs):
        for epoch, level, logger, message, cause, raw in entries(log):
            key = (level, logger, mask(message), mask(cause))
            entry = messages.setdefault(key, {"nodes": {}, "first": epoch, "last": epoch, "example": (node, raw, cause)})
            entry["nodes"][node] = entry["nodes"].get(node, 0) + 1
            entry["first"] = min(entry["first"], epoch)
            entry["last"] = max(entry["last"], epoch)
            step = by_step.setdefault(window_of(epoch, steps), {})
            step[key] = step.get(key, 0) + 1
    ordered = sorted(messages, key=lambda key: messages[key]["first"])
    ids = {key: f"M{number}" for number, key in enumerate(ordered, 1)}

    def time(epoch):
        return datetime.datetime.fromtimestamp(epoch).astimezone().strftime("%H:%M:%S")

    print(f"# Node log digest: {os.path.basename(os.path.abspath(session))}\n")
    print(f"The warnings and errors of {', '.join(os.path.basename(log) for log in logs)}, by message (numbers and "
          "ids masked), then by the measurement step they fell in; a step's window runs from the end of the step "
          "before it, so it includes its warm-up. Times are the host's local time.\n")
    print("| Id | Level | Logger | Message | Cause | " + " | ".join(nodes) + " | First | Last |")
    print("| --- " * (7 + len(nodes)) + "|")
    for key in ordered:
        entry = messages[key]
        level, logger, message, cause = key
        counts = " | ".join(str(entry["nodes"].get(node, 0)) for node in nodes)
        cell = lambda text: text.replace("|", "\\|")
        print(f"| {ids[key]} | {level} | `{logger}` | {cell(message)} | {cell(cause)} | {counts} | "
              f"{time(entry['first'])} | {time(entry['last'])} |")
    print("\n## By step\n")
    print("| Measurement and step | Lines | Messages |")
    print("| --- | ---: | --- |")
    order = {f"{name} {step}": start for start, _, name, step in steps}
    for label in sorted(by_step, key=lambda label: order.get(label, float("inf"))):
        counts = by_step[label]
        listed = ", ".join(f"{ids[key]} × {count}" for key, count in sorted(counts.items(), key=lambda item: ids[item[0]]))
        print(f"| {label} | {sum(counts.values())} | {listed} |")
    print("\n## First line of each\n")
    for key in ordered:
        node, raw, cause = messages[key]["example"]
        print(f"- {ids[key]} ({node}): `{raw[:400]}`" + (f"; cause `{cause[:300]}`" if cause else ""))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit("usage: digest-logs.py <session dir> <node log> [<node log> ...]")
    main(sys.argv[1], sys.argv[2:])
