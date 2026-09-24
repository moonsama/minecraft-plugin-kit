"""Read-only access to the local copies of the legacy Moonsama databases.

The legacy stack (Minecraft API on MySQL, Customizer and Composer on Postgres)
is only needed once, to extract static cosmetics data for the kit. This module
talks to local dumps through the ``mysql`` and ``psql`` command line clients so
no Python database drivers are required.

Connection URLs come from ``.env.local`` at the repository root:

    LOCAL_MYSQL_URL=mysql://user:pass@host:port/db
    LOCAL_ASSETS_POSTGRES_URL=postgresql://user:pass@host:port/db
    LOCAL_CUSTOMIZER_POSTGRES_URL=postgresql://user:pass@host:port/db

Override the client binaries with ``PSQL_BIN`` / ``MYSQL_BIN`` if they are not
on ``PATH`` (Homebrew's libpq installs psql under /opt/homebrew/opt/libpq/bin).
"""

from __future__ import annotations

import csv
import io
import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote, urlparse

REPO_ROOT = Path(__file__).resolve().parents[2]


def load_env_local(path: Path = REPO_ROOT / ".env.local") -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


@dataclass(frozen=True)
class Dsn:
    scheme: str
    user: str
    password: str
    host: str
    port: int
    database: str

    @staticmethod
    def parse(url: str) -> "Dsn":
        parsed = urlparse(url)
        if not parsed.hostname or not parsed.path:
            raise ValueError(f"unusable database url: {url!r}")
        return Dsn(
            scheme=parsed.scheme,
            user=unquote(parsed.username or ""),
            password=unquote(parsed.password or ""),
            host=parsed.hostname,
            port=parsed.port or (3306 if parsed.scheme.startswith("mysql") else 5432),
            database=parsed.path.lstrip("/"),
        )


def _binary(env_name: str, default: str, fallbacks: tuple[str, ...] = ()) -> str:
    configured = os.environ.get(env_name)
    if configured:
        return configured
    found = shutil.which(default)
    if found:
        return found
    for candidate in fallbacks:
        if Path(candidate).exists():
            return candidate
    raise FileNotFoundError(f"{default} not found; set {env_name}")


class Postgres:
    def __init__(self, dsn: Dsn):
        self.dsn = dsn
        self.psql = _binary("PSQL_BIN", "psql", ("/opt/homebrew/opt/libpq/bin/psql", "/usr/local/opt/libpq/bin/psql"))

    def rows(self, query: str) -> list[dict[str, str]]:
        """Run a SELECT and return rows as dicts. Uses CSV so JSON text columns survive."""
        query = query.strip().rstrip(";")
        command = [
            self.psql,
            "-h", self.dsn.host,
            "-p", str(self.dsn.port),
            "-U", self.dsn.user,
            "-d", self.dsn.database,
            "-v", "ON_ERROR_STOP=1",
            "-X", "-q",
            "-c", f"\\copy ({query}) to stdout with (format csv, header true)",
        ]
        env = dict(os.environ, PGPASSWORD=self.dsn.password)
        completed = subprocess.run(command, env=env, capture_output=True, text=True, check=False)
        if completed.returncode != 0:
            raise RuntimeError(f"psql failed: {completed.stderr.strip()}")
        return list(csv.DictReader(io.StringIO(completed.stdout)))


class MySql:
    def __init__(self, dsn: Dsn):
        self.dsn = dsn
        self.mysql = _binary("MYSQL_BIN", "mysql", ("/opt/homebrew/opt/mysql-client/bin/mysql",))

    def rows(self, query: str) -> list[dict[str, str]]:
        command = [
            self.mysql,
            f"--host={self.dsn.host}",
            f"--port={self.dsn.port}",
            f"--user={self.dsn.user}",
            "--batch",
            "--default-character-set=utf8mb4",
            self.dsn.database,
            "-e", query,
        ]
        env = dict(os.environ, MYSQL_PWD=self.dsn.password)
        completed = subprocess.run(command, env=env, capture_output=True, text=True, check=False)
        if completed.returncode != 0:
            raise RuntimeError(f"mysql failed: {completed.stderr.strip()}")
        lines = completed.stdout.splitlines()
        if not lines:
            return []
        header = lines[0].split("\t")
        result = []
        for line in lines[1:]:
            values = [_unescape_mysql(v) for v in line.split("\t")]
            result.append(dict(zip(header, values)))
        return result


def _unescape_mysql(value: str) -> str:
    if value == "NULL":
        return ""
    return value.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")


@dataclass
class LegacyDatabases:
    minecraft_api: MySql
    composer: Postgres
    customizer: Postgres

    @staticmethod
    def from_env() -> "LegacyDatabases":
        env = {**load_env_local(), **os.environ}
        missing = [k for k in ("LOCAL_MYSQL_URL", "LOCAL_ASSETS_POSTGRES_URL", "LOCAL_CUSTOMIZER_POSTGRES_URL") if not env.get(k)]
        if missing:
            raise SystemExit(f"missing in .env.local: {', '.join(missing)}")
        return LegacyDatabases(
            minecraft_api=MySql(Dsn.parse(env["LOCAL_MYSQL_URL"])),
            composer=Postgres(Dsn.parse(env["LOCAL_ASSETS_POSTGRES_URL"])),
            customizer=Postgres(Dsn.parse(env["LOCAL_CUSTOMIZER_POSTGRES_URL"])),
        )
