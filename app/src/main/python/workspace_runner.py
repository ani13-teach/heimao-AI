import base64
import builtins
import io
import json
import os
import sys
import tempfile
import traceback
import types

_LIMIT = 128 * 1024


class _BinaryOutput(io.RawIOBase):
    def __init__(self, owner):
        self.owner = owner

    def writable(self):
        return True

    def write(self, value):
        self.owner.write(bytes(value).decode("utf-8", errors="replace"))
        return len(value)


class _Output(io.TextIOBase):
    def __init__(self):
        self.parts = []
        self.size = 0
        self.truncated = False
        self._buffer = _BinaryOutput(self)

    @property
    def encoding(self):
        return "utf-8"

    @property
    def errors(self):
        return "replace"

    @property
    def buffer(self):
        return self._buffer

    def writable(self):
        return True

    def write(self, value):
        if not isinstance(value, str):
            raise TypeError("write() requires str")
        keep = min(len(value), _LIMIT - self.size)
        if keep:
            self.parts.append(value[:keep])
            self.size += keep
        self.truncated |= keep != len(value)
        return len(value)

    def flush(self):
        pass

    def text(self):
        return "".join(self.parts)


class _Paths:
    """Python-level compatibility aliases, not a filesystem or security boundary."""
    def __init__(self, workspace, home, tmp):
        self.aliases = {"/workspace": workspace, "/root": home, "/tmp": tmp}
        self.originals = []

    def map(self, value):
        if isinstance(value, int) or value is None:
            return value
        path = os.fspath(value)
        binary = isinstance(path, bytes)
        text = os.fsdecode(path)
        for alias, target in self.aliases.items():
            if text == alias or text.startswith(alias + "/"):
                result = target + text[len(alias):]
                return os.fsencode(result) if binary else result
        return value

    def patch(self, owner, name, two=False):
        if not hasattr(owner, name):
            return
        original = getattr(owner, name)
        self.originals.append((owner, name, original))

        def wrapped(*args, **kwargs):
            if args:
                path, args = args[0], args[1:]
            elif "file" in kwargs:
                path = kwargs.pop("file")
            elif "path" in kwargs:
                path = kwargs.pop("path")
            elif name in ("listdir", "scandir"):
                path = "."
            else:
                return original(**kwargs)
            path = self.map(path)
            if two:
                if args:
                    args = (self.map(args[0]),) + args[1:]
                else:
                    for key in ("dst", "destination"):
                        if key in kwargs:
                            kwargs[key] = self.map(kwargs[key])
            return original(path, *args, **kwargs)
        setattr(owner, name, wrapped)

    def __enter__(self):
        for owner, name in ((builtins, "open"), (io, "open")):
            self.patch(owner, name)
        for name in ("open", "stat", "lstat", "listdir", "scandir", "mkdir", "makedirs",
                     "remove", "unlink", "rmdir", "removedirs", "chdir", "chmod", "access",
                     "readlink", "utime", "truncate", "statvfs"):
            self.patch(os, name)
        for name in ("rename", "replace", "link", "symlink"):
            self.patch(os, name, two=True)
        return self

    def __exit__(self, *exc):
        for owner, name, original in reversed(self.originals):
            setattr(owner, name, original)


def _matplotlib():
    import matplotlib
    matplotlib.use("Agg", force=True)
    from matplotlib import font_manager
    from matplotlib.ft2font import FT2Font

    sample = "中文图表数据时间数量行列标题"
    preferred = []
    for root, _, names in os.walk("/system/fonts"):
        for name in sorted(names):
            if name.lower().endswith((".ttf", ".otf", ".ttc")):
                preferred.append(os.path.join(root, name))
    preferred.sort(key=lambda path: ("cjk" not in path.lower(), path))
    for path in preferred:
        try:
            glyphs = FT2Font(path).get_charmap()
            if not all(ord(char) in glyphs for char in sample):
                continue
            font_manager.fontManager.addfont(path)
            family = font_manager.FontProperties(fname=path).get_name()
            matplotlib.rcParams["font.family"] = [family, "DejaVu Sans"]
            matplotlib.rcParams["axes.unicode_minus"] = False
            return
        except (OSError, RuntimeError):
            continue
    raise RuntimeError("No system font with Chinese glyphs is available")


def run(request_json):
    request = json.loads(request_json)
    output, error = _Output(), _Output()
    old_streams = sys.stdin, sys.stdout, sys.stderr
    old_argv, old_path = sys.argv, sys.path[:]
    old_cwd, old_env = os.getcwd(), dict(os.environ)
    old_main = sys.modules.get("__main__")
    old_temp = tempfile.tempdir
    code = 0
    try:
        for key in ("home", "tmp"):
            os.makedirs(request[key], exist_ok=True)
        os.environ.update(HOME=request["home"], TMPDIR=request["tmp"],
                          MPLCONFIGDIR=os.path.join(request["tmp"], "matplotlib"),
                          MPLBACKEND="Agg")
        tempfile.tempdir = request["tmp"]
        os.chdir(request["cwd"])
        stdin = base64.b64decode(request["stdin"]) if request["stdin"] is not None else b""
        sys.stdin = io.TextIOWrapper(io.BytesIO(stdin), encoding="utf-8", errors="replace")
        sys.stdout, sys.stderr = output, error
        with _Paths(request["workspace"], request["home"], request["tmp"]) as paths:
            _matplotlib()
            args = list(request["args"])
            if args and args[0] == "--":
                args.pop(0)
            if args and args[0] == "-c":
                if len(args) < 2:
                    raise ValueError("-c requires a program")
                source, filename, argv, search = args[1], "<string>", ["-c"] + args[2:], request["cwd"]
            elif not args or args[0] == "-":
                source, filename = sys.stdin.read(), "<stdin>"
                argv, search = ["-"] + args[1:], request["cwd"]
            else:
                if args[0].startswith("-"):
                    raise ValueError("Supported Python arguments: -c PROGRAM, SCRIPT, or -")
                filename = os.path.abspath(paths.map(args[0]))
                with open(filename, "rb") as script:
                    source = script.read()
                argv, search = args, os.path.dirname(filename)
            sys.argv = argv
            sys.path.insert(0, search)
            main = types.ModuleType("__main__")
            main.__dict__.update(__builtins__=builtins, __file__=filename, __package__=None,
                                 __spec__=None, __cached__=None)
            sys.modules["__main__"] = main
            exec(compile(source, filename, "exec"), main.__dict__)
    except SystemExit as exc:
        if exc.code is None:
            code = 0
        elif isinstance(exc.code, int):
            code = exc.code
        else:
            code = 1
            error.write(str(exc.code) + "\n")
    except BaseException:
        code = 1
        traceback.print_exc(file=error)
    finally:
        sys.stdin, sys.stdout, sys.stderr = old_streams
        sys.argv = old_argv
        sys.path[:] = old_path
        if old_main is None:
            sys.modules.pop("__main__", None)
        else:
            sys.modules["__main__"] = old_main
        os.chdir(old_cwd)
        os.environ.clear()
        os.environ.update(old_env)
        tempfile.tempdir = old_temp
    return json.dumps({"exitCode": code, "stdout": output.text(), "stderr": error.text(),
                       "truncated": output.truncated or error.truncated}, ensure_ascii=False)
