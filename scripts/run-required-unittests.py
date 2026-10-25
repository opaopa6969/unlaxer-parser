#!/usr/bin/env python3
"""CI 用 unittest runner。skip とゼロ実行も失敗にする（標準 CLI と同じ引数）。"""
from pathlib import Path
import sys
import unittest

# `python3 scripts/run-required-unittests.py scripts.tests.test_...` と discover の両方に対応。
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path.cwd()))


class RequiredResult(unittest.TextTestResult):
    def wasSuccessful(self):
        return super().wasSuccessful() and not self.skipped and self.testsRun > 0


if __name__ == '__main__':
    unittest.main(module=None, testRunner=unittest.TextTestRunner(resultclass=RequiredResult))
