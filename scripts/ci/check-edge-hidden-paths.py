#!/usr/bin/env python3
"""Exercise the committed nginx template using its Dockerfile runtime image.

Only a disposable local container and synthetic fixtures are used. No production
URLs, credentials, provider calls or database access are accepted by this script.
A missing Docker daemon is a failure, not a skipped/passing release check.
"""
from __future__ import annotations

import http.client
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=120).strip()


def request(port: int, path: str, method: str = "GET") -> tuple[int, str]:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    try:
        connection.request(method, path)
        response = connection.getresponse()
        return response.status, response.read().decode("utf-8", errors="replace")
    finally:
        connection.close()


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    template = (root / "clientside/default.conf.template").read_text()
    dockerfile = (root / "clientside/Dockerfile").read_text()
    images = re.findall(r"^FROM\s+(nginx:[^\s]+)", dockerfile, re.MULTILINE)
    if len(images) != 1:
        raise RuntimeError("Expected exactly one nginx runtime image in clientside/Dockerfile")
    for key, value in {"DNS_RESOLVER": "127.0.0.11", "BACKEND_UPSTREAM": "127.0.0.1:18081", "PORT": "8080"}.items():
        template = template.replace("${" + key + "}", value)
    if "${" in template:
        raise RuntimeError("Unresolved nginx environment placeholder")
    # Same-container synthetic backend proves API/readiness routes still proxy.
    template += '\nserver { listen 18081; location / { return 200 "stub-backend\\n"; } }\n'
    name = "cito-edge-test-" + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix="cito-edge-") as temp:
        scratch = Path(temp)
        scratch.chmod(0o755)
        www = scratch / "www"
        fixtures = {
            "index.html": "CITO_SYNTHETIC_SPA",
            "releasez": '{"release_sha":"synthetic-test"}',
            "assets/test.js": "synthetic-asset",
            ".env": "SYNTHETIC_SECRET_NOT_FOR_SERVING",
            ".git/config": "SYNTHETIC_SECRET_NOT_FOR_SERVING",
            ".well-known/security.txt": "synthetic-security-contact",
            ".well-known/.env": "SYNTHETIC_SECRET_NOT_FOR_SERVING",
        }
        for relative, content in fixtures.items():
            target = www / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
        config = scratch / "default.conf"
        config.write_text(template)
        mounts = ["-v", f"{www}:/usr/share/nginx/html:ro", "-v", f"{config}:/etc/nginx/conf.d/default.conf:ro"]
        run("docker", "run", "--rm", *mounts, images[0], "nginx", "-t")
        try:
            run("docker", "run", "--detach", "--rm", "--name", name,
                "--publish", "127.0.0.1::8080", *mounts, images[0], "nginx", "-g", "daemon off;")
            port = int(run("docker", "port", name, "8080/tcp").splitlines()[0].rsplit(":", 1)[1])
            deadline = time.monotonic() + 30
            while True:
                try:
                    if request(port, "/healthz")[0] == 200:
                        break
                except (OSError, http.client.HTTPException):
                    pass
                if time.monotonic() >= deadline:
                    raise RuntimeError("Synthetic nginx did not become healthy")
                time.sleep(0.2)
            checked = 0
            for path in ("/.env", "/.env.production", "/.git/config", "/.svn/entries",
                         "/nested/.env", "/assets/.env", "/api/.env", "/api/ui/.env",
                         "/admins/.env", "/%2eenv", "/%2egit/config", "//.env",
                         "/.well-known", "/.well-known/missing", "/.well-known/.env",
                         "/.well-known-evil/token", "/assets/missing.js"):
                for method in ("GET", "HEAD"):
                    status, body = request(port, path, method)
                    assert status == 404, (method, path, status)
                    assert "CITO_SYNTHETIC_SPA" not in body, (method, path, "SPA fallback")
                    assert "SYNTHETIC_SECRET" not in body, (method, path, "hidden fixture served")
                    checked += 1
            expected = {
                "/": "CITO_SYNTHETIC_SPA", "/bo/deep-link": "CITO_SYNTHETIC_SPA",
                "/status": "CITO_SYNTHETIC_SPA", "/healthz": "ok",
                "/releasez": '{"release_sha":"synthetic-test"}',
                "/assets/test.js": "synthetic-asset",
                "/.well-known/security.txt": "synthetic-security-contact",
                "/readyz": "stub-backend\n", "/api/status/health": "stub-backend\n",
                "/status/health": "stub-backend\n", "/api/ui/test": "stub-backend\n",
                "/api/v2/test": "stub-backend\n", "/admins/test": "stub-backend\n",
                "/api/v2/test?filename=.env": "stub-backend\n",
            }
            for path, expected_body in expected.items():
                status, body = request(port, path)
                assert (status, body) == (200, expected_body), (path, status, body)
                checked += 1
            print(f"PASS: {checked} local nginx HTTP regression cases")
        except BaseException:
            subprocess.run(["docker", "logs", name], check=False, timeout=10)
            raise
        finally:
            subprocess.run(["docker", "rm", "--force", name], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)


if __name__ == "__main__":
    main()
