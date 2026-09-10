#!/usr/bin/env python3
"""Validate Cito frontend/backend delivery parity and environment contract.

The validator intentionally does not try to infer business intent from code. It verifies that
pull requests declare the actual changed application surfaces, require explicit rationale/evidence,
and require an approved exception reference for behavior-bearing one-sided changes.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
from typing import Iterable

REPO = "lynelk/Cito"
CANONICAL_PROJECT_ID = "8d361df2-d17e-4d15-984e-435735f22f6c"
CANONICAL_PRODUCTION_ENVIRONMENT_ID = "bec50941-04c7-426d-8bc3-883cbdece892"
CANONICAL_BACKEND_SERVICE_ID = "ba6fd4b7-e61d-48d1-987d-7f6dc56c1e84"
CANONICAL_FRONTEND_SERVICE_ID = "fc3dae0c-f602-4242-b66f-9ba7e1491f1f"
CANONICAL_DATABASE_SERVICE_ID = "26c20665-9b31-47b6-ac2c-a984c9b0c6d4"

BACKEND_PREFIXES = (
    "InitializrSpringbootProjectFresh/src/main/java/",
    "InitializrSpringbootProjectFresh/src/main/resources/db/migration/",
)
FRONTEND_PREFIXES = ("clientside/src/",)

# One-sided changes in these paths can change capability/process semantics and therefore require
# a deliberate exception reference instead of silently drifting the other application surface.
BACKEND_BEHAVIOR_PREFIXES = BACKEND_PREFIXES
FRONTEND_BEHAVIOR_EXTENSIONS = (".js", ".jsx", ".ts", ".tsx")

DECLARATION_RE = re.compile(
    r"^Frontend/backend parity:\s*(BOTH|BACKEND_ONLY|FRONTEND_ONLY|NONE)\s*$",
    re.MULTILINE,
)
RATIONALE_RE = re.compile(r"^Parity rationale:\s*(.+?)\s*$", re.MULTILINE)
EVIDENCE_RE = re.compile(r"^Parity evidence:\s*(.+?)\s*$", re.MULTILINE)
EXCEPTION_RE = re.compile(r"^Parity exception:\s*(.+?)\s*$", re.MULTILINE)


def fail(message: str) -> None:
    print(f"PARITY GATE FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_environment_contract(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read environment contract {path}: {exc}")


def validate_environment_contract(contract: dict) -> None:
    source = contract.get("sourceOfTruth", {})
    railway = contract.get("railway", {})
    prod = railway.get("production", {})
    staging = railway.get("staging", {})
    services = prod.get("services", {})

    checks = {
        "source repository": (source.get("repository"), REPO),
        "Railway project ID": (railway.get("projectId"), CANONICAL_PROJECT_ID),
        "production environment ID": (
            prod.get("environmentId"),
            CANONICAL_PRODUCTION_ENVIRONMENT_ID,
        ),
        "production branch": (prod.get("branch"), "production"),
        "backend service ID": (
            services.get("backend", {}).get("serviceId"),
            CANONICAL_BACKEND_SERVICE_ID,
        ),
        "frontend service ID": (
            services.get("frontend", {}).get("serviceId"),
            CANONICAL_FRONTEND_SERVICE_ID,
        ),
        "database service ID": (
            services.get("database", {}).get("serviceId"),
            CANONICAL_DATABASE_SERVICE_ID,
        ),
        "backend root": (
            services.get("backend", {}).get("rootDirectory"),
            "InitializrSpringbootProjectFresh",
        ),
        "frontend root": (services.get("frontend", {}).get("rootDirectory"), "clientside"),
        "production commit policy": (
            prod.get("commitPolicy"),
            "BACKEND_FRONTEND_MATCH_BRANCH_HEAD",
        ),
        "staging source branch": (staging.get("branch"), "main"),
        "staging commit policy": (
            staging.get("commitPolicy"),
            "BACKEND_FRONTEND_MATCH_BRANCH_HEAD",
        ),
        "staging auto deploy": (staging.get("autoDeploy"), True),
    }
    mismatches = [
        f"{label}: expected {expected!r}, got {actual!r}"
        for label, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        fail("environment contract mismatch:\n- " + "\n- ".join(mismatches))

    if staging.get("status") not in {"PLANNED", "ACTIVE"}:
        fail("staging status must be PLANNED or ACTIVE")
    if staging.get("status") == "ACTIVE" and not staging.get("environmentId"):
        fail("active staging requires a Railway environmentId")


def changed_files(base_ref: str) -> list[str]:
    subprocess.run(
        ["git", "fetch", "origin", base_ref, "--depth=1"],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    output = subprocess.check_output(
        ["git", "diff", "--name-only", f"origin/{base_ref}...HEAD"], text=True
    )
    return [line.strip() for line in output.splitlines() if line.strip()]


def starts_with_any(path: str, prefixes: Iterable[str]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def expected_declaration(files: list[str]) -> tuple[str, list[str], list[str]]:
    backend = [path for path in files if starts_with_any(path, BACKEND_PREFIXES)]
    frontend = [path for path in files if starts_with_any(path, FRONTEND_PREFIXES)]
    if backend and frontend:
        return "BOTH", backend, frontend
    if backend:
        return "BACKEND_ONLY", backend, frontend
    if frontend:
        return "FRONTEND_ONLY", backend, frontend
    return "NONE", backend, frontend


def require_field(regex: re.Pattern[str], body: str, label: str, minimum: int) -> str:
    match = regex.search(body)
    if not match:
        fail(f"PR body must contain `{label}: ...`")
    value = match.group(1).strip()
    if len(value) < minimum:
        fail(f"{label} is too short to be meaningful")
    return value


def validate_pr(event: dict) -> None:
    pr = event.get("pull_request")
    if not pr:
        return

    body = pr.get("body") or ""
    base_ref = pr.get("base", {}).get("ref")
    if not base_ref:
        fail("unable to resolve PR base branch")

    files = changed_files(base_ref)
    expected, backend, frontend = expected_declaration(files)

    declaration = DECLARATION_RE.search(body)
    if not declaration:
        fail(
            "PR body must contain `Frontend/backend parity: BOTH|BACKEND_ONLY|FRONTEND_ONLY|NONE`"
        )
    actual = declaration.group(1)
    if actual != expected:
        fail(
            f"declared parity {actual}, but changed files require {expected}. "
            f"backend files={len(backend)}, frontend files={len(frontend)}"
        )

    require_field(RATIONALE_RE, body, "Parity rationale", 30)
    require_field(EVIDENCE_RE, body, "Parity evidence", 15)
    exception = require_field(EXCEPTION_RE, body, "Parity exception", 4)

    one_sided_behavior = False
    if expected == "BACKEND_ONLY":
        one_sided_behavior = any(
            starts_with_any(path, BACKEND_BEHAVIOR_PREFIXES) for path in backend
        )
    elif expected == "FRONTEND_ONLY":
        one_sided_behavior = any(path.endswith(FRONTEND_BEHAVIOR_EXTENSIONS) for path in frontend)

    if one_sided_behavior and exception.lower() in {
        "none",
        "n/a",
        "na",
        "not applicable",
        "not-applicable",
    }:
        fail(
            "behavior-bearing one-sided application change requires an approved Parity exception "
            "reference (issue/ADR/decision), or the corresponding other surface must change in the PR"
        )

    print(
        "Parity declaration accepted: "
        f"{actual}; backend files={len(backend)}; frontend files={len(frontend)}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", type=pathlib.Path)
    parser.add_argument(
        "--contract",
        type=pathlib.Path,
        default=pathlib.Path("ops/environments/cito-environments.json"),
    )
    args = parser.parse_args()

    validate_environment_contract(load_environment_contract(args.contract))
    print("Environment contract accepted")

    if args.event and args.event.exists():
        try:
            event = json.loads(args.event.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            fail(f"cannot read GitHub event: {exc}")
        validate_pr(event)


if __name__ == "__main__":
    main()
