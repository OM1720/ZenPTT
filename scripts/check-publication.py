"""Check Git's index or outgoing history without changing the working tree."""

import fnmatch
from functools import lru_cache
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile


GITLEAKS_VERSION = "8.30.1"
ROOT = Path(__file__).resolve().parents[1]
PREFIXES = (
    "private/", "dist/", "support/", "acceptance/artifacts/", ".venv/",
    "web/test-results/", "web/playwright-report/", "server/releases/",
)
SECRET_FILES = (
    "*.apk", "*.aab", "*.keystore", "*.jks", "*.pem", "*.p12", "*.pfx",
    "*.key", "*.log", "*.zip", "*.7z", "*.tar", "*.tgz", "*.gz", "*.bundle",
    "*.bak", "*.backup", "id_rsa*", "id_ed25519*", "id_ecdsa*",
)
SUBJECT = re.compile(
    r"(?:feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)"
    r"(?:\([a-z0-9][a-z0-9._/-]*\))?!?: [A-Za-z0-9][\x20-\x7e]*"
)


class Rejected(Exception):
    pass


def git(*args, data=None, allowed=(0,)):
    result = subprocess.run(
        ["git", "-C", str(ROOT), *args], input=data, capture_output=True, check=False
    )
    if result.returncode not in allowed:
        raise Rejected("Git inspection failed; check the repository and retry.")
    return result.stdout


def config(name):
    value = git("config", "--get", name, allowed=(0, 1)).decode().strip()
    if not value:
        raise Rejected("Run scripts/setup-publication.ps1 before publishing.")
    return value


def scanner():
    local = ROOT / "private/tools/gitleaks" / GITLEAKS_VERSION / "gitleaks.exe"
    executable = str(local) if local.is_file() else shutil.which("gitleaks")
    if not executable:
        raise Rejected("Gitleaks is missing. Run scripts/setup-publication.ps1.")
    result = subprocess.run([executable, "version"], capture_output=True, check=False)
    if result.returncode or result.stdout.decode().strip().lstrip("v") != GITLEAKS_VERSION:
        raise Rejected(f"Publication checks require Gitleaks {GITLEAKS_VERSION}.")
    return executable


def scan(executable, mode, *args, data=None):
    # Ignore local suppressions and redact findings before they reach the console.
    with tempfile.TemporaryDirectory(prefix="zenptt-scan-") as temporary:
        ignore = Path(temporary) / ".gitleaksignore"
        ignore.write_text("", encoding="utf-8")
        command = [
            executable, mode, *args, "--config", str(ROOT / ".gitleaks.toml"),
            "--gitleaks-ignore-path", str(ignore), "--ignore-gitleaks-allow",
            "--redact=100", "--no-banner", "--log-level=error", "--verbose",
        ]
        result = subprocess.run(command, cwd=ROOT, input=data, capture_output=True, check=False)
        if result.returncode:
            details = (result.stdout + result.stderr).decode("utf-8", errors="replace")
            if details.strip():
                print(details.strip(), file=sys.stderr)
            raise Rejected("Secret scan failed. Review the redacted findings; do not bypass hooks.")


def forbidden(path):
    name = PurePosixPath(path).name.lower()
    lower = path.lower()
    if lower in {"server.local.env", "server/releases/readme.md"}:
        return False
    return (
        lower.startswith(PREFIXES)
        or any(part in {".git", ".ssh", ".aws", "build", "__pycache__", "node_modules"}
               for part in PurePosixPath(lower).parts)
        or name in {"signing.properties", "local.properties", "credentials", ".env"}
        or (name.endswith(".env") or name.startswith(".env.")) and not name.endswith(".example")
        or any(fnmatch.fnmatchcase(name, pattern) for pattern in SECRET_FILES)
    )


def entries(data):
    result = []
    for entry in data.split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        fields = metadata.split()
        path = raw_path.decode("utf-8")
        if fields[0] not in (b"100644", b"100755"):
            raise Rejected(f"Unsupported Git entry (symlink or submodule): {path}")
        if fields[1] != b"blob" and fields[2] != b"0":
            raise Rejected(f"Unresolved index entry: {path}")
        result.append(path)
    return result


def check_paths(paths):
    blocked = [path for path in paths if forbidden(path)]
    if blocked:
        raise Rejected("Private or generated paths cannot be published:\n" + "\n".join(blocked))


@lru_cache(maxsize=1)
def private_domains():
    env_file = ROOT / "server.env"
    if not env_file.is_file():
        return []
    result = []
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        if line.startswith("ZENPTT_DOMAIN="):
            domain = line.partition("=")[2].strip().lower()
            if domain and ".example." not in domain and not domain.endswith(".example.com"):
                result.append(domain.encode())
    return result


def check_local_details(data, label):
    if any(domain in data.lower() for domain in private_domains()):
        raise Rejected(f"Local deployment domain found in {label}; use a placeholder.")


def check_snapshot(executable, worktree=False):
    paths = entries(git("ls-files", "--stage", "-z"))
    if worktree:
        paths += git("ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")
        paths = [path for path in paths if path and (ROOT / path).exists()]
    check_paths(paths)
    ignored = git("check-ignore", "--no-index", "--stdin", "-z",
                  data="\0".join(paths).encode() + b"\0", allowed=(0, 1))
    if ignored:
        raise Rejected("Ignored files are tracked; remove them from the index before committing.")
    with tempfile.TemporaryDirectory(prefix="zenptt-publication-") as temporary:
        destination = Path(temporary)
        if worktree:
            for path in paths:
                source = ROOT / path
                if source.is_symlink() or not source.is_file():
                    raise Rejected(f"Unsupported working-tree entry: {path}")
                target = destination / path
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        else:
            git("checkout-index", "--all", "--prefix=" + destination.as_posix() + "/")
        for path in paths:
            check_local_details((destination / path).read_bytes(), path)
        scan(executable, "dir", str(destination))


def check_message(data):
    message = data.decode("utf-8")
    subject = message.splitlines()[0] if message.splitlines() else ""
    if len(subject) > 72 or not SUBJECT.fullmatch(subject):
        raise Rejected("Use an English Conventional Commits subject, at most 72 characters, "
                       "such as: fix(android): restore audio after reconnect")
    check_local_details(data, "commit message")


def check_push(executable, remote_url):
    if remote_url != config("zenptt.publicRemote"):
        raise Rejected("Unexpected push destination; review the local publication configuration.")
    expected_root = config("zenptt.publicRoot")
    scanned = set()
    for line in sys.stdin.read().splitlines():
        parts = line.split()
        if len(parts) != 4:
            raise Rejected("Invalid pre-push input.")
        _, local_sha, _, _ = parts
        if not re.fullmatch(r"[0-9a-f]{40,64}", local_sha):
            raise Rejected("Invalid pushed object ID.")
        if not local_sha.strip("0"):
            raise Rejected("Remote ref deletion requires a separate reviewed operation.")
        commit = git("rev-parse", local_sha + "^{commit}").decode().strip()
        roots = git("rev-list", "--max-parents=0", commit).decode().splitlines()
        if roots != [expected_root]:
            raise Rejected("Push contains a different Git root; private legacy history is forbidden.")
        tag_object = local_sha
        while git("cat-file", "-t", tag_object).strip() == b"tag":
            tag = git("cat-file", "tag", tag_object)
            check_local_details(tag, "tag metadata")
            scan(executable, "stdin", data=tag)
            tag_object = tag.splitlines()[0].split()[1].decode("ascii")
        # Scan all reachable history, including new branches and deleted secrets.
        for revision in git("rev-list", commit).decode().splitlines():
            if revision in scanned:
                continue
            scanned.add(revision)
            paths = entries(git("ls-tree", "-r", "-z", revision))
            check_paths(paths)
            message = git("show", "-s", "--format=%B", revision)
            if revision != expected_root:
                check_message(message)
            scan(executable, "stdin", data=message)
            tree = git("show", "--format=", "--root", "-m", "--no-ext-diff", "--no-textconv",
                       "--binary", revision)
            check_local_details(tree, "commit " + revision[:12])
        scan(executable, "git", str(ROOT), "--log-opts=--full-history -m " + commit)


def main():
    mode = sys.argv[1]
    executable = scanner()
    if mode in {"staged", "worktree"}:
        check_snapshot(executable, worktree=mode == "worktree")
    elif mode == "message":
        data = Path(sys.argv[2]).read_bytes()
        check_message(data)
        scan(executable, "stdin", data=data)
    elif mode == "push":
        check_push(executable, sys.argv[3])
    else:
        raise Rejected("Unknown publication check mode.")
    print("Publication checks passed (" + mode + ").")


if __name__ == "__main__":
    try:
        main()
    except (Rejected, OSError, UnicodeError, IndexError) as error:
        print("Publication blocked: " + str(error), file=sys.stderr)
        sys.exit(1)
