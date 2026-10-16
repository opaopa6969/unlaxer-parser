#!/usr/bin/env python3
"""The dependency rewrite must restore original POM bytes across partial failures."""
import importlib.util
import pathlib
import signal
import tempfile
import unittest
from unittest.mock import patch

script=pathlib.Path(__file__).resolve().parents[1]/'check-tiny-production.py'
spec=importlib.util.spec_from_file_location('tiny_production',script)
guard=importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

class TinyDependenciesTest(unittest.TestCase):
    def exercise(self, rewrite_failure=False, body_failure=False, terminate=False):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            modules=[root/'tiny',root/'ide']
            originals={}
            for index,module in enumerate(modules):
                module.mkdir()
                path=module/'pom.xml'
                originals[path]=f'<project>original {index}</project>\r\n'.encode()
                path.write_bytes(originals[path])
            calls=[]
            def rewrite(command,cwd):
                calls.append(command)
                (cwd/'pom.xml').write_text('changed')
                if rewrite_failure and len(calls)==2: raise SystemExit(17)
            compatibility=[(label,module,'Test',1) for label,module in zip(['tiny','ide'],modules)]
            previous=signal.getsignal(signal.SIGTERM)
            with patch.object(guard,'COMPATIBILITY',compatibility),patch.object(guard,'run',side_effect=rewrite):
                def execute():
                    with guard.current_dependencies('3.3.0-SNAPSHOT'):
                        self.assertEqual(2,len(calls))
                        self.assertTrue(all(path.read_text()=='changed' for path in originals))
                        if terminate: signal.raise_signal(signal.SIGTERM)
                        if body_failure: raise TimeoutError('downstream timed out')
                if rewrite_failure or terminate:
                    with self.assertRaises(SystemExit): execute()
                elif body_failure:
                    with self.assertRaises(TimeoutError): execute()
                else: execute()
            self.assertEqual(previous,signal.getsignal(signal.SIGTERM))
            for path,content in originals.items(): self.assertEqual(content,path.read_bytes())
            for command in calls:
                self.assertTrue('-DdepVersion=3.3.0-SNAPSHOT' in command or '-DnewVersion=3.3.0-SNAPSHOT' in command)
                self.assertIn('-DgenerateBackupPoms=false',command)

    def test_success_restores_both_poms(self): self.exercise()
    def test_partially_failed_second_rewrite_restores_both_poms(self): self.exercise(rewrite_failure=True)
    def test_downstream_timeout_restores_both_poms(self): self.exercise(body_failure=True)
    def test_termination_restores_both_poms(self): self.exercise(terminate=True)


    def test_old_success_xml_cannot_replace_missing_new_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            reports=root/'target/surefire-reports'
            reports.mkdir(parents=True)
            old=reports/'TEST-example.Test.xml'
            old.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
            def no_new_report(*args): self.assertFalse(old.exists())
            with patch.object(guard,'run',side_effect=no_new_report):
                with self.assertRaisesRegex(SystemExit,'missing or duplicate'):
                    guard.verify_compatibility('tiny',root,'Test',1)

    def test_skip_does_not_satisfy_compatibility_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            reports=root/'target/surefire-reports'
            reports.mkdir(parents=True)
            def skipped_report(*args):
                (reports/'TEST-example.Test.xml').write_text('<testsuite tests="1" failures="0" errors="0" skipped="1"/>')
            with patch.object(guard,'run',side_effect=skipped_report):
                with self.assertRaisesRegex(SystemExit,'invalid tiny'):
                    guard.verify_compatibility('tiny',root,'Test',1)

if __name__=='__main__': unittest.main()
