"""Exercise real Java launches with a path that cannot be safely word-split."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts.java_tmp import java_command

ROOT = Path(__file__).resolve().parents[2]


class JavaTemporaryDirectoryTest(unittest.TestCase):
    def test_without_tmpdir_the_launcher_keeps_its_default(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(['java'], java_command('java'))
            self.assertEqual(['javac'], java_command('javac'))

    def test_compiler_java_and_native_fixture_launcher_use_the_requested_directory(self):
        java = Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin/java'
        javac = java.with_name('javac')
        self.assertTrue(java.is_file() and javac.is_file(), 'JDK required for CI temp regression')
        with tempfile.TemporaryDirectory(prefix='ci tmp 日本語 ') as directory:
            root = Path(directory).resolve()
            source = root / 'TempProbe.java'
            source.write_text('''import java.nio.file.*;
class TempProbe {
  public static void main(String[] args) throws Exception {
    if (!Path.of(System.getenv("JAVA_HOME"), "bin/java").toRealPath().equals(
        Path.of(System.getProperty("java.home"), "bin/java").toRealPath()))
      throw new AssertionError("JAVA_HOME must select the real JDK");
    Path temp = Files.createTempDirectory("python-java-child-");
    try { System.out.print(temp.toRealPath().getParent()); }
    finally { Files.delete(temp); }
  }
}
''')
            with patch.dict(os.environ, TMPDIR=str(root), JAVA_HOME=str(java.parent.parent)):
                compile_result = subprocess.run(java_command(javac) + [str(source)],
                                                capture_output=True, text=True, timeout=30)
                self.assertEqual(0, compile_result.returncode, compile_result.stderr)
                self.assertEqual('', compile_result.stderr)
                for command in [java_command(java), [str(ROOT / 'scripts/ci/java-tmpdir/bin/java')]]:
                    result = subprocess.run(command + ['-cp', str(root), 'TempProbe'],
                                            capture_output=True, text=True, timeout=20)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual('', result.stderr)
                    self.assertEqual(str(root), result.stdout)


if __name__ == '__main__':
    unittest.main()
