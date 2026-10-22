"""Bounded Git reads must reuse immutable blobs, never incomplete results."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('budget_gate', Path(__file__).parents[1] / 'verify-pin-citations.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReadBudgetTest(unittest.TestCase):
    def test_cached_and_uncached_attribution_match_for_mixed_pins(self):
        a, b = 'a' * 40, 'b' * 40
        text = f'## Pin\n| Desktop | `{a}` |\n| narrower | `{b}` | *(registry)* |\n\n## Claims\n`lib/a.ts:1`\n`lib/b.ts:2` @ `{b}`\n`lib/c.ts:3` *(registry)*\n'
        plain = text.splitlines()
        cached = gate.AnalyzedLines(plain)
        for number, path in ((6, 'lib/a.ts'), (7, 'lib/b.ts'), (8, 'lib/c.ts')):
            self.assertEqual(gate.citation_pin(plain, number, [a, b], path),
                             gate.citation_pin(cached, number, [a, b], path))
        self.assertEqual(gate.citation_pin(cached, 6, [a, b], 'lib/a.ts'), a)

    def test_repeated_declaration_scans_reuse_line_analysis(self):
        pin = 'a' * 40
        text = '## Pin\n' + pin + '\n' + '\n'.join('ordinary prose' for _ in range(100)) + '\n' + '\n'.join('`lib/a.ts:1`' for _ in range(20))
        with patch.object(gate, 'resolve_path', return_value='lib/a.ts'), patch.object(gate, '_revision_tokens', wraps=gate._revision_tokens) as parse:
            bound, _ = gate.citation_bindings(text, [pin])
        self.assertEqual(len(bound), 1)
        self.assertLess(parse.call_count, 150, 'each source line needs analysis once, not once per citation')

    def test_same_blob_across_revisions_is_read_only_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True).strip()
            def commit(message):
                git('add', '-A')
                git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', message)
                return git('rev-parse', 'HEAD')
            git('init', '-q')
            (root / 'same.ts').write_text('one\n\nthree\n')
            old = commit('old')
            (root / 'other.ts').write_text('different\n')
            new = commit('new')
            calls = []
            original = gate.subprocess_run
            def observed(command, **kwargs):
                calls.append(command)
                return original(command, **kwargs)
            with patch.object(gate, 'UPSTREAM', root), patch.object(gate, 'subprocess_run', side_effect=observed):
                self.assertEqual(gate.blob(old, 'same.ts'), ['one', '', 'three'])
                self.assertEqual(gate.blob(new, 'same.ts'), ['one', '', 'three'])
            reads = [c for c in calls if 'show' in c]
            self.assertEqual(len(reads), 1, 'identical immutable bytes need one bounded blob read, not one per revision')


if __name__ == '__main__':
    unittest.main()
