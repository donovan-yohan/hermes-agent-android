"""The Classic preset uses a double-quoted description containing an apostrophe."""
import importlib.util
from pathlib import Path
import sys
import unittest

PATH = Path(__file__).resolve().parents[2] / '.chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py'
spec = importlib.util.spec_from_file_location('theme_parity', PATH)
assert spec is not None and spec.loader is not None
parity = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = parity
spec.loader.exec_module(parity)


class ThemeParityParserTest(unittest.TestCase):
    def test_double_quoted_classic_description_and_converter_palette(self):
        source = '''export const classicTheme: DesktopTheme = {
  name: 'classic',
  label: 'Classic Hermes',
  description: "Gold on navy, the CLI's original look",
  colors: classicPalette(CLASSIC_LIGHT_SKIN_COLORS),
  darkColors: classicPalette(CLASSIC_DARK_SKIN_COLORS)
}
export const BUILTIN_THEMES: Record<string, DesktopTheme> = {
  classic: classicTheme
}
export const DEFAULT_SKIN_NAME = 'nous'
'''
        themes, default = parity.parse_desktop(source, {})
        self.assertEqual('nous', default)
        self.assertEqual([parity.Preset('classic', 'Classic Hermes', "Gold on navy, the CLI's original look", True)], themes)


if __name__ == '__main__':
    unittest.main()
