#!/usr/bin/env python3
# ANONYMISATION PROVENANCE FOR fixtures/real_backup.json
# ======================================================
#
# fixtures/real_backup.json was originally a REAL user export of the Questa PWA
# (task titles, personal notes, mental-health habit labels, device IDs, an
# embedded avatar image, and two years of precise timestamps). It was never
# intended to be published.
#
# This script is the record of what was changed to make it safe to publish.
# Keeping it in the repo means anyone can verify that claim rather than take it
# on trust, and re-run it if the fixture is ever regenerated.
#
# WHAT IT DOES
#   * Replaces every personal string value with a synthetic sample name, using
#     an injective map so distinct inputs stay distinct and the tests that assert
#     on counts keep working.
#   * Regenerates all UUIDs (preserving version/variant nibbles), device IDs and
#     per-event UIDs deterministically.
#   * Replaces the embedded avatar JPEG with a neutral placeholder.
#   * Shifts every timestamp back by exactly 3640 days - 520 whole weeks, so
#     weekday alignment and every interval between events are preserved and the
#     day/streak logic the tests exercise is unchanged.
#
# WHAT IT DELIBERATELY PRESERVES
#   * Structure, key order, array lengths and element counts.
#   * Every numeric literal, byte for byte (numbers are parsed into a RawNum
#     wrapper that keeps the original literal text).
#   * Schema enum values and the FM field-name legend.
#   * The app version and the questa-*.json filename shapes.
#   * NOTE: _backup.hash is NOT recomputed here. It is the SHA-256 of the
#     canonical import pre-image, so it must be recomputed with the project's
#     own SerializationEngine / CanonicalJson code after sanitising.
#
# USAGE
#   python tools/sanitize_fixture.py <input.json> <output.json> [report.txt]
#
# VERIFYING
#   - structure identical to the input
#   - every numeric leaf either unchanged or shifted by exactly 3640 days
#   - no original free-text value survives verbatim

import json, re, sys, io, datetime, hashlib

KEEP_VALUES = {
    "daily", "todo", "habit", "char",
    "easy", "medium", "hard", "trivial", "log",
    "completed", "created", "incomplete", "metric", "overview",
    "day", "tag", "type", "bar", "heatmap", "line", "list",
    "all", "due", "manual", "active", "created-desc",
    "30d", "90d",
    "analytics", "dailies", "habits", "todos",
    "local", "remote", "hidden", "Warrior", "prefs", "tasks",
    "equal ts, field-merged", "newest export wins",
    "added", "ba", "changed", "removed", "toggled",
    "checklist", "counter", "difficulty", "notes", "title",
    "a", "b", "c",
}

LEGEND_SUBTREES = {"FM"}          # field-name legend: pure schema, never touch
SHIFT_DAYS = 3640                 # exactly 520 weeks -> weekday alignment kept
EPOCH_LO, EPOCH_HI = 1_600_000_000_000, 1_900_000_000_000


class RawNum(str):
    """A JSON number, carried as its original literal text."""


UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
TOKEN_RE = re.compile(r"\bmr[a-z0-9]{5,14}\b")
REPUID_RE = re.compile(r"\brep-[0-9a-f]{8}\b")
EPOCH_RE = re.compile(r"(?<![\d.])(1[6-9]\d{11})(?![\d.])")

ISO_Z = re.compile(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?Z")
ISO_P = re.compile(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?")
SPACE = re.compile(r"(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})")
US = re.compile(r"(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})")
COMPACT = re.compile(r"(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)")
APPVER_RE = re.compile(r"^v\d{4}\.\d{2}\.\d{2}-\d+$")
FILENAME_RE = re.compile(r"^questa-[\w.-]+\.json$")
HEX64_RE = re.compile(r"^[0-9a-f]{64}$")


def _shift(dt):
    return dt - datetime.timedelta(days=SHIFT_DAYS)


def _iso(rx, suffix_fn):
    def f(m):
        g = m.groups()
        y, mo, d, h, mi, s = (int(x) for x in g[:6])
        frac = g[6] if len(g) > 6 else None
        try:
            dt = datetime.datetime(y, mo, d, h, mi, s, tzinfo=datetime.timezone.utc)
        except ValueError:
            return m.group(0)
        n = _shift(dt)
        return suffix_fn(n, frac)
    return f


def _fmt_space(n, frac):
    return f"{n.year:04d}-{n.month:02d}-{n.day:02d} {n.hour:02d}:{n.minute:02d}:{n.second:02d}"


def _fmt_z(n, frac):
    return f"{n.year:04d}-{n.month:02d}-{n.day:02d}T{n.hour:02d}:{n.minute:02d}:{n.second:02d}{frac or ''}Z"


def _fmt_p(n, frac):
    return f"{n.year:04d}-{n.month:02d}-{n.day:02d}T{n.hour:02d}:{n.minute:02d}:{n.second:02d}{frac or ''}"


def _shift_us(m):
    mo, d, y, h, mi, s = (int(m.group(i)) for i in (1, 2, 3, 4, 5, 6))
    try:
        dt = datetime.datetime(y, mo, d, h, mi, s, tzinfo=datetime.timezone.utc)
    except ValueError:
        return m.group(0)
    return _fmt_space(_shift(dt), None)


def _compact(m):
    y, mo, d = int(m.group("cy")), m.group("cmo"), m.group("cd")
    if not ("01" <= mo <= "12" and "01" <= d <= "31"):
        return m.group(0)
    try:
        n = _shift(datetime.date(y, int(mo), int(d)))
    except ValueError:
        return m.group(0)
    return f"{n.year:04d}{n.month:02d}{n.day:02d}"


def _fmt(n, frac, kind):
    core = f"{n.year:04d}-{n.month:02d}-{n.day:02d}"
    if kind == "space":
        return f"{core} {n.hour:02d}:{n.minute:02d}:{n.second:02d}"
    tail = frac or ""          # frac already carries its leading dot
    return f"{core}T{n.hour:02d}:{n.minute:02d}:{n.second:02d}{tail}" + ("Z" if kind == "Z" else "")


def _shift_date(m):
    """ONE combined pass over every date form. Separate sequential shifters
    would re-shift each other's output (ISO_P matches what ISO_Z produced)."""
    g = m.groupdict()
    if g.get("cy"):
        return _compact(m)
    if g.get("mm"):                                   # MM/DD/YYYY
        y, mo, d, h, mi, s = g["yy"], g["mm"], g["dd"], g["h4"], g["mi4"], g["s4"]
        frac, kind = None, "space"
    else:
        i = "" if g.get("y") else ("2" if g.get("y2") else "3")
        y, mo, d = g[f"y{i}"], g[f"mo{i}"], g[f"d{i}"]
        h, mi, s = g[f"h{i}"], g[f"mi{i}"], g[f"s{i}"]
        frac = g.get(f"frac{i}")
        kind = "Z" if m.group(0).endswith("Z") else "plain"
    try:
        dt = datetime.datetime(int(y), int(mo), int(d), int(h), int(mi), int(s),
                               tzinfo=datetime.timezone.utc)
    except ValueError:
        return m.group(0)
    return _fmt(_shift(dt), frac, kind)


DATE_ANY = re.compile(
    r"(?<![0-9])(?P<y>\d{4})-(?P<mo>\d{2})-(?P<d>\d{2})T(?P<h>\d{2}):(?P<mi>\d{2}):(?P<s>\d{2})(?P<frac>\.\d+)?Z"
    r"|(?<![0-9])(?P<y2>\d{4})-(?P<mo2>\d{2})-(?P<d2>\d{2})T(?P<h2>\d{2}):(?P<mi2>\d{2}):(?P<s2>\d{2})(?P<frac2>\.\d+)?"
    r"|(?<![0-9])(?P<y3>\d{4})-(?P<mo3>\d{2})-(?P<d3>\d{2}) (?P<h3>\d{2}):(?P<mi3>\d{2}):(?P<s3>\d{2})"
    r"|(?<![0-9])(?P<mm>\d{2})/(?P<dd>\d{2})/(?P<yy>\d{4}) (?P<h4>\d{2}):(?P<mi4>\d{2}):(?P<s4>\d{2})"
    r"|(?<![0-9])(?P<cy>20\d{2})(?P<cmo>\d{2})(?P<cd>\d{2})(?![0-9])"
)

SHIFTERS = [(DATE_ANY, _shift_date)]


def det_uuid(u):
    h = hashlib.sha256(("questa-sanitize::" + u).encode()).hexdigest()
    return f"{h[0:8]}-{h[8:12]}-{u[14]}{h[13:16]}-{u[19]}{h[17:20]}-{h[20:32]}"


def det_token(t):
    h = hashlib.sha256(("questa-sanitize::" + t).encode()).hexdigest()
    al = "abcdefghijklmnopqrstuvwxyz0123456789"
    return "mr" + "".join(al[int(h[i * 2:i * 2 + 2], 16) % 36] for i in range(1, 13))


def det_repid(r):
    return "rep-" + hashlib.sha256(("questa-sanitize::" + r).encode()).hexdigest()[:8]


# --------------------------------------------------------------- serializer
def dump(node, out):
    if isinstance(node, dict):
        out.append("{")
        first = True
        for k, v in node.items():
            if not first:
                out.append(",")
            first = False
            out.append(json.dumps(k, ensure_ascii=False))
            out.append(":")
            dump(v, out)
        out.append("}")
    elif isinstance(node, list):
        out.append("[")
        for i, v in enumerate(node):
            if i:
                out.append(",")
            dump(v, out)
        out.append("]")
    elif isinstance(node, RawNum):
        out.append(str(node))
    elif isinstance(node, bool):
        out.append("true" if node else "false")
    elif node is None:
        out.append("null")
    else:
        out.append(json.dumps(node, ensure_ascii=False))


def collect(node, in_legend, acc):
    if isinstance(node, dict):
        for k, v in node.items():
            if in_legend:
                continue
            collect(v, in_legend or k in LEGEND_SUBTREES, acc)
    elif isinstance(node, list):
        for v in node:
            collect(v, in_legend, acc)
    elif isinstance(node, str) and not isinstance(node, RawNum):
        acc.add(node)


def main():
    src, dst, rep = sys.argv[1], sys.argv[2], sys.argv[3]
    raw = io.open(src, encoding="utf-8").read()
    doc = json.loads(raw, parse_float=RawNum, parse_int=RawNum)

    acc = set()
    collect(doc, False, acc)

    # Iterate in sorted order: Python set iteration order depends on
    # PYTHONHASHSEED, so an unsorted pass would assign different synthetic
    # labels on every run and this script would not reproduce its own output.
    replace_map, kept = {}, set()
    for s in sorted(acc):
        if s == "" or s in KEEP_VALUES:
            kept.add(s); continue
        if UUID_RE.fullmatch(s) or TOKEN_RE.fullmatch(s) or REPUID_RE.fullmatch(s):
            continue
        if any(rx.fullmatch(s) for rx, _ in SHIFTERS):
            continue
        # Structured, non-personal: keep the shape and let the date passers
        # handle any embedded timestamps instead of flattening to a sample string.
        if APPVER_RE.match(s) or FILENAME_RE.match(s) or HEX64_RE.match(s):
            continue
        replace_map[s] = None

    # longest-first so "Ráno" cannot clobber the inside of "Ráno aj deťom";
    # ties broken by value so the ordering is fully deterministic
    ordered = sorted(replace_map, key=lambda s: (-len(s), s))
    for i, s in enumerate(ordered):
        replace_map[s] = ("data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJ"
                          "CQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wB"
                          "DAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy"
                          "MjIyMjIyMjL/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAEC"
                          "AwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB"
                          "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNk"
                          "ZWmnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG"
                          "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAEC"
                          "AwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFE"
                          "KRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWV"
                          "jZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3"
                          "uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMB"
                          "AAIRAxEAPwD3+iiigD//2Q=="
                          if s.startswith("data:image") else f"Sample item {i:04d}")

    stats = {"replaced": 0, "uuid": 0, "token": 0,
             "date": 0, "epoch": 0}

    # NB: the merge-conflict records under S.ex[].cc[].a LOOK like dumped
    # objects, but they are stored as real nested JSON objects, so recursion
    # above reaches their leaf strings directly. No substring rewriting is
    # needed (and substring rewriting is actively dangerous here: a short
    # label would match inside UUIDs and epoch-millis numbers).

    def transform(node, in_legend=False):
        if isinstance(node, dict):
            return {k: transform(v, in_legend or k in LEGEND_SUBTREES)
                    for k, v in node.items()}
        if isinstance(node, list):
            return [transform(v, in_legend) for v in node]
        if isinstance(node, RawNum):
            lit = str(node)
            if not in_legend and lit.isdigit():
                v = int(lit)
                if EPOCH_LO <= v <= EPOCH_HI:
                    stats["epoch"] += 1
                    return RawNum(str(v - SHIFT_DAYS * 86_400_000))
            return node
        if isinstance(node, str):
            if in_legend or node == "" or node in KEEP_VALUES:
                return node
            if node in replace_map:
                stats["replaced"] += 1
                return replace_map[node]
            if UUID_RE.fullmatch(node):
                stats["uuid"] += 1
                return det_uuid(node)
            if REPUID_RE.fullmatch(node):
                return det_repid(node)
            if TOKEN_RE.fullmatch(node):
                stats["token"] += 1
                return det_token(node)
            t = node
            if EPOCH_RE.search(t):
                stats["epoch"] += 1
                t = EPOCH_RE.sub(lambda m: str(int(m.group(1)) - SHIFT_DAYS * 86_400_000), t)
            for rx, fn in SHIFTERS:
                t = rx.sub(fn, t)
            if t != node:
                stats["date"] += 1
            return t
        return node

    new = transform(doc)

    buf = []
    dump(new, buf)
    out = "".join(buf)
    json.loads(out, parse_float=RawNum, parse_int=RawNum)   # hard validation

    io.open(dst, "w", encoding="utf-8", newline="").write(out)

    residual = set()
    collect(json.loads(out), False, residual)
    with io.open(rep, "w", encoding="utf-8") as f:
        f.write("stats: " + json.dumps(stats) + "\n")
        f.write(f"replaced distinct strings: {len(replace_map)}\n")
        f.write(f"kept enum/structural values ({len(kept)}):\n")
        for s in sorted(kept):
            f.write(f"    KEEP {s!r}\n")
        f.write(f"\nresidual distinct strings ({len(residual)}):\n")
        for s in sorted(residual):
            f.write(f"    {s!r}\n")


if __name__ == "__main__":
    main()
