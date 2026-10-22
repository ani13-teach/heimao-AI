"""Host checks for adapter semantics; Android libraries/fonts are tested on device."""
import ast
import base64
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "main/python/workspace_runner.py"
namespace = {"__name__": "workspace_runner"}
exec(compile(SOURCE.read_text(encoding="utf-8"), str(SOURCE), "exec"), namespace)
namespace["_matplotlib"] = lambda: None


class WorkspaceRunnerHostTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.files = self.root / "files"
        self.files.mkdir()
        self.cwd = self.files / "nested"
        self.cwd.mkdir()
        self.original_cwd = os.getcwd()
        self.original_streams = sys.stdin, sys.stdout, sys.stderr
        self.original_argv = sys.argv
        self.original_path = sys.path[:]
        self.original_env = dict(os.environ)

    def execute(self, args, stdin=None):
        result = json.loads(namespace["run"](json.dumps({
            "args": args, "stdin": None if stdin is None else base64.b64encode(stdin).decode(),
            "workspace": str(self.files), "cwd": str(self.cwd),
            "home": str(self.root / "home"), "tmp": str(self.root / "tmp"),
        })))
        self.assertEqual(self.original_cwd, os.getcwd())
        self.assertEqual(self.original_streams, (sys.stdin, sys.stdout, sys.stderr))
        self.assertIs(self.original_argv, sys.argv)
        self.assertEqual(self.original_path, sys.path)
        self.assertEqual(self.original_env, dict(os.environ))
        return result

    def test_program_args_stdin_and_aliases(self):
        result = self.execute(["-c", """
import os, sys, io
assert sys.argv == ['-c', 'argument']
assert sys.stdin.read() == 'input'
with open(file='/workspace/out.txt', mode='w') as f: f.write('ok')
assert io.open('/workspace/out.txt').read() == 'ok'
open('/root/home.txt', 'w').close()
open('/tmp/temp.txt', 'w').close()
assert os.stat('/workspace/out.txt').st_size == 2
assert isinstance(os.listdir(), list)
print('ok')
""", "argument"], b"input")
        self.assertEqual(0, result["exitCode"], result["stderr"])
        self.assertEqual("ok\n", result["stdout"])
        self.assertTrue((self.root / "home/home.txt").exists())
        self.assertTrue((self.root / "tmp/temp.txt").exists())

    def test_script_and_stdin_program(self):
        (self.cwd / "script.py").write_text("import sys; print(sys.argv[1])", encoding="utf-8")
        result = self.execute(["script.py", "value"])
        self.assertEqual(0, result["exitCode"], result["stderr"])
        self.assertEqual("value\n", result["stdout"])
        self.assertEqual("stdin\n", self.execute([], b"print('stdin')")["stdout"])
        self.assertEqual("dash\n", self.execute(["-"], b"print('dash')")["stdout"])

    def test_error_exit_and_bounded_streams(self):
        self.assertIn("ValueError: expected", self.execute(["-c", "raise ValueError('expected')"])["stderr"])
        self.assertEqual(7, self.execute(["-c", "raise SystemExit(7)"])["exitCode"])
        self.assertEqual(1, self.execute(["-c"])["exitCode"])
        result = self.execute(["-c", "import sys; print('x'*300000); sys.stderr.buffer.write(b'e'*300000)"])
        self.assertEqual(128 * 1024, len(result["stdout"]))
        self.assertEqual(128 * 1024, len(result["stderr"]))
        self.assertTrue(result["truncated"])

    def test_ast(self):
        ast.parse(SOURCE.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
