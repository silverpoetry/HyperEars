"""Verify OPPO built-in exclusions against a supplied Leaf 929d813f device_models.json.

Read-only/offline: python tools/verify-oppo-eq-catalog.py PATH_TO_PINNED_JSON
All conditional and nested EQ IDs are excluded conservatively, without firmware inference.
"""
import argparse
import json
import re
from pathlib import Path


def eq_ids(node, in_eq=False):
    if isinstance(node, list):
        for value in node:
            yield from eq_ids(value, in_eq)
    elif isinstance(node, dict):
        for key, value in node.items():
            if in_eq and key == "protocolIndex" and isinstance(value, int):
                yield value
            else:
                yield from eq_ids(value, in_eq or key.startswith("equalizerMode"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    source = json.loads(args.source.read_text(encoding="utf-8"))["whiteList"]
    expected = {}
    for record in source:
        ids = set(eq_ids(record))
        if ids:
            expected[int(record["id"], 16)] = ids
    catalog = Path(__file__).resolve().parents[1] / "integration/src/main/java/dev/hyperears/integration/OppoBuiltInEqIds.kt"
    actual = {
        int(product, 16): {int(value.strip()) for value in values.split(",") if value.strip()}
        for product, values in re.findall(r"0x([0-9A-Fa-f]+) to setOf\(([^)]*)\)", catalog.read_text(encoding="utf-8"))
    }
    assert actual == expected, {
        f"{product:06X}": {"missing": sorted(expected.get(product, set()) - actual.get(product, set())),
                           "extra": sorted(actual.get(product, set()) - expected.get(product, set()))}
        for product in expected.keys() | actual.keys() if expected.get(product) != actual.get(product)
    }
    print(f"Verified {len(actual)} product exclusions, {sum(map(len, actual.values()))} built-in IDs against full source.")


if __name__ == "__main__":
    main()
