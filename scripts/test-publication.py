"""Exercise publication hooks only in disposable repositories and local remotes."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[1]
SCANNER_DIRECTORY = PROJECT / "private/tools/gitleaks/8.30.1"
# Synthetic scanner fixture, assembled so the test source contains no credential.
TOKEN = "gh" + "p_" + "aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY3zA5"


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="zenptt-hook-test-")
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)
        self.repo = self.workspace / "source"
        self.repo.mkdir()
        self.env = os.environ.copy()
        self.env["PATH"] = str(SCANNER_DIRECTORY) + os.pathsep + self.env["PATH"]
        self.env["GIT_CONFIG_NOSYSTEM"] = "1"
        self.env["GIT_CONFIG_GLOBAL"] = str(self.workspace / "empty-config")
        (self.workspace / "empty-config").write_text("", encoding="utf-8")
        # Never inherit a caller's alternate index or repository when making fixtures.
        for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE"):
            self.env.pop(name, None)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Publication Test")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "gc.auto", "0")
        self.git("config", "core.hooksPath", ".githooks")
        for relative in (
            ".gitignore", ".gitleaks.toml", ".gitattributes",
            ".githooks/pre-commit", ".githooks/commit-msg", ".githooks/pre-push",
            "scripts/check-publication.py", "scripts/check-publication.ps1",
        ):
            target = self.repo / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(PROJECT / relative, target)
            if relative.startswith(".githooks/"):
                target.chmod(0o755)
        self.commit_fixture("chore: initialize fixture")
        self.initial = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("config", "zenptt.publicRoot", self.initial)
        self.remote = self.workspace / "remote.git"
        self.git("init", "--bare", str(self.remote))
        self.git("remote", "add", "origin", str(self.remote))
        self.git("config", "zenptt.publicRemote", str(self.remote))

    def git(self, *args, success=True):
        result = subprocess.run(
            ["git", *args], cwd=self.repo, env=self.env, capture_output=True,
            text=True, encoding="utf-8", errors="replace", check=False,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def commit_fixture(self, subject):
        self.git("add", "-A")
        self.git("-c", "core.hooksPath=" + str(self.workspace / "no-hooks"),
                 "commit", "-m", subject)

    def guard(self, mode, success=True):
        result = subprocess.run(
            [sys.executable, str(self.repo / "scripts/check-publication.py"), mode],
            cwd=self.repo, env=self.env, capture_output=True, text=True, check=False,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def rejected(self, result, reason):
        self.assertNotEqual(result.returncode, 0)
        output = result.stdout + result.stderr
        self.assertIn(reason, output)
        self.assertNotIn(TOKEN, output, "Scanner output must not expose fixture secrets")

    def test_real_commit_and_local_push_hooks_accept_clean_source(self):
        self.write("server.env", "ZENPTT_DOMAIN=ptt.operator.net\n")
        self.write("note.md", "A reviewed source change.\n")
        self.git("add", "note.md")
        commit = self.git("commit", "-m", "docs: describe the source change")
        self.assertIn("Publication checks passed (message)", commit.stdout + commit.stderr)
        push = self.git("push", "origin", "HEAD:refs/heads/main")
        self.assertIn("Publication checks passed (push)", push.stdout + push.stderr)

    def test_forced_ignored_file_is_blocked(self):
        self.write("private/test-host.json", '{"host": "ptt.operator.net"}')
        self.git("add", "-f", "private/test-host.json")
        result = self.git("commit", "-m", "chore: add host settings", success=False)
        self.rejected(result, "Private or generated paths")
        self.assertEqual(self.git("rev-parse", "HEAD").stdout.strip(), self.initial)

    def test_staged_secret_is_detected_even_when_worktree_is_cleaned(self):
        self.write("sample.txt", "token = " + TOKEN + "\n")
        self.git("add", "sample.txt")
        self.write("sample.txt", "The working copy no longer contains a token.\n")
        self.rejected(self.guard("staged", success=False), "Secret scan failed")

    def test_local_domain_is_not_published(self):
        self.write("server.env", "ZENPTT_DOMAIN=ptt.operator.net\n")
        self.write("note.md", "Connect to ptt.operator.net\n")
        self.rejected(self.guard("worktree", success=False), "Local deployment domain")

    def test_secret_deleted_in_later_commit_still_blocks_new_branch_push(self):
        self.write("sample.txt", "token = " + TOKEN + "\n")
        self.commit_fixture("chore: add fixture")
        self.git("rm", "sample.txt")
        self.commit_fixture("chore: remove fixture")
        result = self.git("push", "origin", "HEAD:refs/heads/topic", success=False)
        self.rejected(result, "Secret scan failed")
        missing = self.git("--git-dir=" + str(self.remote), "rev-parse", "--verify",
                           "refs/heads/topic", success=False)
        self.assertNotEqual(missing.returncode, 0)

    def test_private_file_deleted_in_later_commit_still_blocks_push(self):
        self.write("dist/hosting/transfer.txt", "Delivery metadata\n")
        self.git("add", "-f", "dist/hosting/transfer.txt")
        self.commit_fixture("chore: add delivery fixture")
        self.git("rm", "dist/hosting/transfer.txt")
        self.commit_fixture("chore: remove delivery fixture")
        self.rejected(self.git("push", "origin", "HEAD", success=False),
                      "Private or generated paths")

    def test_message_hook_rejects_nonconforming_subject(self):
        result = self.git("commit", "--allow-empty", "-m", "Changed stuff", success=False)
        self.rejected(result, "English Conventional Commits")
        self.assertEqual(self.git("rev-parse", "HEAD").stdout.strip(), self.initial)

    def test_annotated_tag_message_is_scanned(self):
        self.git("tag", "-a", "v0.0.0", "-m", "token = " + TOKEN)
        self.git("tag", "-a", "outer", "v0.0.0", "-m", "Public tag wrapper")
        self.rejected(self.git("push", "origin", "refs/tags/outer", success=False),
                      "Secret scan failed")

    def test_missing_scanner_blocks_publication(self):
        self.env["PATH"] = str(Path(shutil.which("git")).parent)
        self.rejected(self.guard("staged", success=False), "Gitleaks is missing")

    def test_unexpected_remote_is_blocked(self):
        self.git("config", "zenptt.publicRemote", "https://example.com/unexpected.git")
        self.rejected(self.git("push", "origin", "HEAD", success=False),
                      "Unexpected push destination")

    def test_unrelated_history_is_blocked(self):
        self.git("checkout", "--orphan", "legacy")
        self.commit_fixture("chore: seed unrelated history")
        self.rejected(self.git("push", "origin", "HEAD", success=False),
                      "private legacy history is forbidden")


if __name__ == "__main__":
    unittest.main(verbosity=2)
