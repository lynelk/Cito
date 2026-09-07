#!/usr/bin/env python3
"""Check the engineering brand mirror and explicit PR assessment, not full UX."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

HEX_COLOR = re.compile(r"#[0-9A-Fa-f]{6}\Z")


def ratio(a: str, b: str) -> float:
    def lum(value: str) -> float:
        channels = [int(value[index : index + 2], 16) / 255 for index in (1, 3, 5)]
        linear = [
            channel / 12.92
            if channel <= 0.04045
            else ((channel + 0.055) / 1.055) ** 2.4
            for channel in channels
        ]
        return sum(value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722)))

    low, high = sorted((lum(a), lum(b)))
    return (high + 0.05) / (low + 0.05)


def validate_colors(value: Any, path: str = "color") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            validate_colors(child, f"{path}.{key}")
        return
    if not isinstance(value, str) or not HEX_COLOR.fullmatch(value):
        raise ValueError(f"Brand token {path} must be an exact six-digit hex color")


def verify(root: Path, event: dict[str, Any] | None = None) -> None:
    latest = json.loads((root / "LATEST.json").read_text(encoding="utf-8"))

    def member(name: str) -> Path:
        path = (root / name).resolve()
        if not path.is_relative_to(root.resolve()):
            raise ValueError("Brand pointer leaves its directory")
        return path

    tokens = json.loads(member(latest["tokens"]).read_text(encoding="utf-8"))
    standard = member(latest["standard"]).read_text(encoding="utf-8")
    metadata = tokens["_meta"]
    mirrored_fields = {
        "brand": "brand",
        "version": "version",
        "status": "status",
        "effective_date": "date",
    }
    for pointer_field, token_field in mirrored_fields.items():
        if latest[pointer_field] != metadata[token_field]:
            raise ValueError(
                f"Token {token_field} does not match current brand pointer {pointer_field}"
            )

    version = latest["version"]
    if f"Version {version}" not in standard:
        raise ValueError("Standard version does not match current brand pointer")

    colors = tokens["color"]
    validate_colors(colors)
    for key, value in colors["brand"].items():
        if value not in standard:
            raise ValueError(f"Brand anchor absent from standard: {key}")

    pairs = [
        (colors["text"]["body"], colors["surface"]["card"], 4.5),
        (colors["text"]["muted"], colors["surface"]["page"], 4.5),
        (colors["text"]["inverse"], colors["action"]["primary"], 4.5),
        (colors["border"]["control"], colors["surface"]["page"], 3),
    ]
    pairs += [
        (colors["status"][status], colors["surface"][status], 4.5)
        for status in ("success", "warning", "error", "info")
    ]
    if any(ratio(foreground, background) < minimum for foreground, background, minimum in pairs):
        raise ValueError("Approved reference contrast pair failed")
    if tokens["targetPx"]["preferredMinimum"] < 44:
        raise ValueError("Preferred control target is below the brand baseline")

    if event and "pull_request" in event:
        body = event["pull_request"].get("body") or ""
        match = re.search(r"^Brand version:\s*([^\r\n]+)$", body, re.M | re.I)
        if not match or match.group(1).strip() != version:
            raise ValueError(f"PR must include Brand version: {version}")
        impact = re.search(r"^Brand impact:\s*([^\r\n]+)$", body, re.M | re.I)
        if not impact or "[" in impact.group(1):
            raise ValueError(
                "PR must describe Brand impact or explain why it is not applicable"
            )
        assessment = impact.group(1).strip()
        not_applicable = re.fullmatch(
            r"not applicable(?:\s*[-—:]\s*|\s+because\s+)(.{12,})",
            assessment,
            re.I,
        )
        if assessment.lower().startswith("not applicable") and not not_applicable:
            raise ValueError("A not-applicable Brand impact must include a specific reason")
        if len(assessment) < 12:
            raise ValueError("Brand impact assessment is too short")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--event", type=Path)
    args = parser.parse_args()
    try:
        event = (
            json.loads(args.event.read_text(encoding="utf-8")) if args.event else None
        )
        verify(args.root, event)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Brand check failed: {error}", file=sys.stderr)
        return 1
    print(
        "Brand mirror and applicable PR assessment checks passed. "
        "Full UX and release review remain required."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
