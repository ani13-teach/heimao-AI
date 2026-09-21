#!/usr/bin/env python3
"""Build the independent probe with local Android tools; no project Gradle changes."""
import argparse
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=Path('C:/Android/Sdk'))
    parser.add_argument('--jdk', type=Path, default=Path('C:/DevTools/jdk-21'))
    args = parser.parse_args()
    build_tools = args.sdk / 'build-tools/37.0.0'
    android_jar = args.sdk / 'platforms/android-37.0/android.jar'
    llvm = args.sdk / 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64'
    java = args.jdk / 'bin/java.exe'
    javac = args.jdk / 'bin/javac.exe'
    keytool = args.jdk / 'bin/keytool.exe'
    out = ROOT / 'build'
    classes = out / 'classes'
    dex = out / 'dex'
    for directory in (out, classes, dex):
        directory.mkdir(parents=True, exist_ok=True)

    def run(*cmd):
        subprocess.run([str(x) for x in cmd], check=True, cwd=ROOT)

    # This package contains no production code, signing keys or user data.
    run(javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-bootclasspath', android_jar, '-d', classes,
        *sorted((ROOT / 'src').rglob('*.java')))
    run(java, '-cp', build_tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '26', '--lib', android_jar, '--output', dex,
        *sorted(classes.rglob('*.class')))
    libraries = []
    for abi, target in [('arm64-v8a', 'aarch64-linux-android26'),
                        ('x86_64', 'x86_64-linux-android26')]:
        lib = out / abi / 'libruntimeprobe.so'
        lib.parent.mkdir(parents=True, exist_ok=True)
        run(llvm / 'bin/clang.exe', f'--target={target}',
            f'--sysroot={llvm / "sysroot"}', '-std=gnu11', '-O2', '-Wall',
            '-Wextra', '-Werror', '-fPIC', '-shared', '-Wl,-z,max-page-size=16384',
            ROOT / 'native/probe.c', '-o', lib)
        libraries.append((abi, lib))
    unsigned = out / 'probe-unsigned.apk'
    run(build_tools / 'aapt2.exe', 'link', '-I', android_jar,
        '--manifest', ROOT / 'AndroidManifest.xml', '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(dex / 'classes.dex', 'classes.dex')
        for abi, lib in libraries:
            apk.write(lib, f'lib/{abi}/{lib.name}')
    aligned = out / 'probe-aligned.apk'
    run(build_tools / 'zipalign.exe', '-f', '4', unsigned, aligned)
    # Disposable probe-only signing key. Does not use the app's release key.
    keystore = out / 'probe-debug.jks'
    if not keystore.exists():
        run(keytool, '-genkeypair', '-keystore', keystore, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'probe', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '3650',
            '-dname', 'CN=Runtime Probe Debug')
    apk = out / 'Heimao-Runtime-Probe.apk'
    run(java, '-jar', build_tools / 'lib/apksigner.jar', 'sign',
        '--ks', keystore, '--ks-key-alias', 'probe', '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android', '--out', apk, aligned)
    run(java, '-jar', build_tools / 'lib/apksigner.jar', 'verify', '--verbose', apk)
    run(build_tools / 'aapt2.exe', 'dump', 'badging', apk)
    print(f'Built: {apk}')


if __name__ == '__main__':
    main()
