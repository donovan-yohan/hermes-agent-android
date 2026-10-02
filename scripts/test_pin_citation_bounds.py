"""Deterministic timeout tests: no network or real sleeps."""
import contextlib
import io
import importlib.util
from typing import Any
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SPEC = importlib.util.spec_from_file_location("citations", pathlib.Path(__file__).with_name("verify-pin-citations.py"))
assert SPEC is not None and SPEC.loader is not None
m: Any = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(m)


class GitBoundsTest(unittest.TestCase):
    def test_commit_probe_timeout_is_not_missing(self):
        process = MagicMock(pid=12345, returncode=-9)
        process.__enter__.return_value = process
        def communicate(*args, **kwargs):
            if kwargs.get("timeout") is not None:
                raise subprocess.TimeoutExpired("SECRET/private", 30, stderr="credential")
            return "", ""
        process.communicate.side_effect = communicate
        with patch.object(m.subprocess, "Popen", return_value=process), patch("os.killpg") as kill:
            with self.assertRaisesRegex(RuntimeError, "commit-probe.*timed out") as error:
                m._has("a" * 40)
        self.assertNotIn("SECRET", str(error.exception))
        self.assertNotIn("credential", str(error.exception))
        kill.assert_called_once()


    def setUp(self):
        m._DEADLINE = None
        m._FETCH = False
        m._materialized.clear()
        m._tree_paths.clear()
        m._blobs.clear()

    def test_cli_timeout_exits_three_not_success(self):
        output = io.StringIO()
        with patch("sys.argv", ["verify", "--check-range", "base..head"]), patch.object(m, "check_range_spec", side_effect=m.GitFailure("git stage=fetch: timed out")), contextlib.redirect_stdout(output):
            with self.assertRaises(SystemExit) as exit:
                m.main()
        self.assertEqual(exit.exception.code, 3)
        self.assertIn("citation", output.getvalue())
        self.assertIn("fetch: timed out", output.getvalue())

    def test_deadline_prevents_starting_another_git(self):
        m._DEADLINE = 100
        with patch.object(m.time, "monotonic", return_value=100), patch.object(m.subprocess, "Popen") as start:
            with self.assertRaisesRegex(RuntimeError, "budget exhausted"):
                m._has("a" * 40)
        start.assert_not_called()

    def test_remote_failure_does_not_disclose_output_or_argv(self):
        process = MagicMock(returncode=128)
        process.communicate.return_value = ("", "https://user:SECRET@host/private")
        with patch.object(m.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(RuntimeError, "fetch: exit 128") as error:
                m.subprocess_run(["git", "fetch", "https://user:SECRET@host/private"], check=True)
        self.assertNotIn("SECRET", str(error.exception))
        self.assertNotIn("private", str(error.exception))

    def test_missing_blob_in_existing_carrier_is_not_skipped(self):
        replies = [subprocess.CompletedProcess([], 128, "", "blob-read failed"),
                   subprocess.CompletedProcess([], 0, "carrier.md\n", "")]
        with patch.object(m, "subprocess_run", side_effect=replies):
            with self.assertRaisesRegex(RuntimeError, "blob-read failed"):
                m.text_at("private", "HEAD", "carrier.md")

    def test_transport_failure_is_not_unreachable_or_success(self):
        binding = {("source.py", ((1, 1),), "a" * 40): 1}
        with patch.object(m, "changed_files", return_value=["carrier.md"]), patch.object(m, "text_at", side_effect=["old", "new"]), patch.object(m, "citation_bindings", side_effect=[({}, 0), (binding, 0)]), patch.object(m, "ensure_sha", side_effect=m.GitFailure("git stage=fetch: timed out")):
            with self.assertRaisesRegex(m.GitFailure, "fetch: timed out"):
                m.check_range("base", "head", fetch=True)

    def test_all_read_commands_are_offline_and_bounded(self):
        process = MagicMock(returncode=0)
        process.communicate.return_value = ("", "")
        with patch.object(m.subprocess, "Popen", return_value=process) as start:
            m._has("a" * 40)
        self.assertEqual(start.call_args.kwargs["env"]["GIT_ALLOW_PROTOCOL"], "")
        self.assertEqual(start.call_args.kwargs["env"]["GIT_NO_LAZY_FETCH"], "1")
        self.assertTrue(start.call_args.kwargs["start_new_session"])
        self.assertLessEqual(process.communicate.call_args.kwargs["timeout"], 30)

    def test_blob_timeout_is_not_absence(self):
        m._blobs.clear()
        m._tree_paths["a" * 40] = ["source.py"]
        process = MagicMock(pid=12345)
        process.communicate.side_effect = subprocess.TimeoutExpired("secret", 30)
        with patch.object(m.subprocess, "Popen", return_value=process), patch("os.killpg"):
            with self.assertRaisesRegex(RuntimeError, "blob-read.*timed out"):
                m.blob("a" * 40, "source.py")
        self.assertNotIn(("a" * 40, "source.py"), m._blobs)

    def test_real_partial_clone_batches_without_mutating_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            origin, partial = root / "origin", root / "partial"
            def git(*args):
                return subprocess.run(["git", *map(str, args)], capture_output=True,
                                      text=True, check=True, timeout=10).stdout.strip()
            git("init", "-q", origin)
            git("-C", origin, "config", "uploadpack.allowFilter", "true")
            (origin / "one.py").write_text("one\n")
            (origin / "two.py").write_text("two\n")
            git("-C", origin, "add", ".")
            git("-C", origin, "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "--no-gpg-sign", "-qm", "fixture")
            sha = git("-C", origin, "rev-parse", "HEAD")
            git("clone", "--filter=blob:none", "--no-checkout", origin.as_uri(), partial)
            def snapshot():
                return {str(p.relative_to(partial)): p.read_bytes()
                        for p in partial.rglob("*") if p.is_file()}
            before = snapshot()
            with patch.object(m, "UPSTREAM", partial):
                try:
                    with self.assertRaisesRegex(m.GitFailure, "blob-read"):
                        m.blob(sha, "one.py")
                    with patch.object(m, "subprocess_run", wraps=m.subprocess_run) as run:
                        m.materialize(sha)
                        self.assertEqual(m.blob(sha, "one.py"), ["one"])
                        self.assertEqual(m.blob(sha, "two.py"), ["two"])
                        m.materialize(sha)
                    fetches = [call for call in run.call_args_list if "fetch" in call.args[0]]
                    self.assertEqual(len(fetches), 1)
                    self.assertEqual(before, snapshot())
                finally:
                    if m._SCRATCH is not None:
                        m._SCRATCH.cleanup()
                    m._SCRATCH = m._READ = None

    def test_complete_snapshot_does_not_require_a_remote(self):
        with patch.object(m, "_has", return_value=True), patch.object(m, "subprocess_run", return_value=subprocess.CompletedProcess([], 0, "b" * 40 + "\n", "")), patch.object(m, "_scratch_clone") as clone:
            m.materialize("a" * 40)
        clone.assert_not_called()
        self.assertIn("a" * 40, m._materialized)

    def test_materialization_once_per_immutable_revision(self):
        m._materialized.clear()
        with patch.object(m, "_has", return_value=False), patch.object(m, "_scratch_clone", return_value=pathlib.Path("scratch")), patch.object(m, "subprocess_run") as run:
            m.materialize("a" * 40)
            m.materialize("a" * 40)
        self.assertEqual(run.call_count, 1)
        self.assertIn("--no-filter", run.call_args.args[0])
        self.assertIn("--refetch", run.call_args.args[0])

    def test_fetch_timeout_is_not_cached(self):
        m._materialized.clear()
        process = MagicMock(pid=12345)
        process.communicate.side_effect = subprocess.TimeoutExpired("credential", 60)
        with patch.object(m, "_has", return_value=False), patch.object(m, "_scratch_clone", return_value=pathlib.Path("scratch")), patch.object(m.subprocess, "Popen", return_value=process), patch("os.killpg"):
            with self.assertRaisesRegex(RuntimeError, "fetch.*timed out"):
                m.materialize("a" * 40)
        self.assertFalse(m._materialized)


if __name__ == "__main__":
    unittest.main()
