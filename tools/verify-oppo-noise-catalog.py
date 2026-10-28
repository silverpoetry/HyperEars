"""Offline verification of OppoNoiseCatalog against Leaf 929d813f device_models.json."""
import argparse
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
args = parser.parse_args()
keys = {1: "OFF", 2: "TRANSPARENCY", 3: "LIGHT", 4: "DEEP", 5: "ANC", 6: "LISTEN",
        7: "SMART", 8: "MEDIUM", 10: "ADAPTIVE"}
expected = {}
for record in json.loads(args.source.read_text(encoding="utf-8"))["whiteList"]:
    modes = record.get("function", {}).get("noiseReductionMode", [])
    if not modes:
        continue
    result = []
    for mode in modes:
        kind = mode["modeType"]
        if kind not in keys:
            continue
        result.append((keys[kind], mode["protocolIndex"], not mode.get("decideByEarDevice", False)))
        for child in mode.get("childrenMode", []):
            child_kind = child.get("modeType", kind)
            key = "VOICE" if kind == 2 and child_kind == 6 else keys.get(child_kind)
            if key is not None:
                result.append((key, child["protocolIndex"], child_kind != kind and
                               not mode.get("decideByEarDevice", False) and not child.get("decideByEarDevice", False)))
    expected[int(record["id"], 16)] = result
catalog = Path(__file__).resolve().parents[1] / "integration/src/main/java/dev/hyperears/integration/OppoNoiseCatalog.kt"
actual = {}
for product, entries in re.findall(r"0x([0-9A-F]+) to listOf\((.*)\),", catalog.read_text(encoding="utf-8")):
    actual[int(product, 16)] = [(key, int(index), disabled != "false") for key, index, disabled in
                               re.findall(r"o\(OppoNoiseMode\.(\w+),(\d+)(?:,(false))?\)", entries)]
assert actual == expected, "Per-product noise mappings differ from pinned source extraction"
assert all(len({index for _, index, _ in rows}) == len(rows) for rows in actual.values())
assert all(0 <= index <= 15 for rows in actual.values() for _, index, _ in rows)
print(f"Verified {len(actual)} products, {sum(map(len, actual.values()))} known report slots; conditional slots remain read-only.")
