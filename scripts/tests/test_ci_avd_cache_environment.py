"""Host fixtures canonicalize their own temp root, never runtime AVD inputs."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AvdCacheEnvironmentTest(unittest.TestCase):
    def test_valid_controls_under_symlinked_inherited_tmpdir(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            real = root / 'real'
            real.mkdir()
            alias = root / 'alias'
            alias.symlink_to(real, target_is_directory=True)
            for target in (
                'test_ci_avd_cache.AvdCacheTest.test_action_duplicate_config_append_is_semantically_equal',
                'test_ci_avd_cache_fresh_restore.FreshRestoreAttackTest.test_control_intact_restore_and_identical_appended_core',
            ):
                with self.subTest(target=target):
                    result = subprocess.run(
                        [sys.executable, '-B', '-m', 'unittest', target],
                        cwd=ROOT / 'scripts/tests',
                        env={**os.environ, 'TMPDIR': str(alias), 'PYTHONDONTWRITEBYTECODE': '1'},
                        capture_output=True, text=True, timeout=30,
                    )
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
