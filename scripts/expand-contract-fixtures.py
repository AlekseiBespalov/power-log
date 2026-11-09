import json
import shutil
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(root / "shared/scripts"))
from protocol_fixtures import expand_fixture, overlay

out = Path(sys.argv[1])
shutil.copytree(root / "tests/fixtures/contract", out)
for directory in ("health",):
    for path in (out / directory).glob("*.json"):
        path.write_text(json.dumps(expand_fixture(json.loads(path.read_text()))))

analytics = {path: json.loads(path.read_text()) for path in (out / "analytics").glob("*.json")}


def expand_analytics(value, index=None):
    if isinstance(value, list):
        return [expand_analytics(item, index) for item in value]
    if not isinstance(value, dict):
        return value
    if "$ref" in value:
        base = expand_analytics(references[value["$ref"]], index)
        return overlay(base, expand_analytics(value["override"], index)) if "override" in value else base
    if "$repeat" in value:
        spec = value["$repeat"]
        return [expand_analytics(spec["template"], i) for i in range(spec["count"])]
    if "$concat" in value:
        return [item for part in value["$concat"] for item in expand_analytics(part, index)]
    if "$values" in value:
        column = value["$values"]
        values = column if isinstance(column, list) else expand_analytics(column, index)
        return expand_analytics(values[index], index)
    if "$cycle" in value:
        values = value["$cycle"]
        return expand_analytics(values[index % len(values)], index)
    if "$entries" in value:
        return dict(expand_analytics(value["$entries"], index))
    if "$linear" in value:
        spec = value["$linear"]
        number = spec["start"] + spec["step"] * index
        if "prefix" in spec:
            return spec["prefix"] + str(number) + spec.get("suffix", "")
        return number
    if "$time" in value:
        spec = value["$time"]
        milliseconds = spec["startMs"] + spec["stepMs"] * index
        return f'{spec["date"]}T{milliseconds // 3600000:02}:{milliseconds // 60000 % 60:02}:{milliseconds // 1000 % 60:02}.{milliseconds % 1000:03}Z'
    return {key: expand_analytics(item, index) for key, item in value.items()}


for path, root in analytics.items():
    references = root.get("shared", {})
    path.write_text(json.dumps(expand_analytics({key: value for key, value in root.items() if key != "shared"})))
