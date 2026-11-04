"""Build pinned, unmodified CPython/Flowseal in Linux; emit an Android AAR's inputs.

Run through tools/build.py on Windows, Linux or macOS.
Sources/toolchains stay in /work (Docker volume); only artifacts go to /out.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

MODULE = Path('/module')
WORK = Path('/work')
OUT = Path('/out')
LOCK = json.loads((MODULE / 'upstream.json').read_text())
HOSTS = {'arm64-v8a': 'aarch64-linux-android',
         'armeabi-v7a': 'arm-linux-androideabi', 'x86_64': 'x86_64-linux-android'}


def run(args, cwd=None, env=None):
    print('+', ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def download(url, digest=None, algorithm='sha256'):
    target = WORK / 'downloads' / url.rsplit('/', 1)[-1]
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        partial = target.with_suffix(target.suffix + '.partial')
        run(['curl', '-fL', '--retry', '5', '--retry-all-errors', '-o', partial, url])
        partial.replace(target)
    actual = hashlib.file_digest(target.open('rb'), algorithm).hexdigest()
    if digest and actual != digest:
        raise RuntimeError(f'Checksum mismatch: {target.name}: {actual}')
    return target


def source_zip(repo, pin):
    archive = download(f'https://codeload.github.com/{repo}/zip/{pin["ref"]}', pin['archive_sha256'])
    target = WORK / 'sources' / (repo.split('/')[-1] + '-' + pin['ref'])
    if not target.exists():
        target.parent.mkdir(exist_ok=True)
        run(['unzip', '-q', archive, '-d', target.parent])
    return target


def prepare_ndk():
    android = LOCK['android']
    sdk = WORK / 'sdk'
    ndk = sdk / 'ndk' / android['ndk_version']
    if not ndk.exists():
        archive = download(android['ndk_url'], android['ndk_sha1'], 'sha1')
        ndk.parent.mkdir(parents=True, exist_ok=True)
        run(['unzip', '-q', archive, '-d', ndk.parent])
        (ndk.parent / 'android-ndk-r27d').rename(ndk)
    # The official helper tests for sdkmanager's marker; the standalone archive
    # has source.properties instead. Validate the actual version before marking.
    assert f'Pkg.Revision = {android["ndk_version"]}' in (ndk / 'source.properties').read_text()
    if not (ndk / 'package.xml').exists():
        (ndk / 'package.xml').write_text(
            f'<localPackage path="ndk;{android["ndk_version"]}"/>\n')
    os.environ['ANDROID_HOME'] = str(sdk)
    os.environ['ANDROID_API_LEVEL'] = str(android['min_api'])
    return ndk


def build_python(source, host, jobs):
    helper = source / 'Android/android.py'
    build = source / 'cross-build/build'
    if not (build / 'python').exists():
        run([sys.executable, helper, 'configure-build', '--', '--without-ensurepip',
             '--disable-test-modules'])
        run(['make', f'-j{jobs}'], cwd=build)
    target = source / 'cross-build' / host
    prefix = target / 'prefix'
    if not (prefix / 'lib/libpython3.13.so').exists():
        run([sys.executable, helper, 'configure-host', host, '--', '--disable-test-modules'])
        run(['make', f'-j{jobs}'], cwd=target)
        run(['make', 'install', f'prefix={prefix}'], cwd=target)
    return prefix


def package_native(prefix, abi, ndk):
    dest = OUT / 'jniLibs' / abi
    dest.mkdir(parents=True, exist_ok=True)
    # Only generated libraries in this ABI's output directory are replaced.
    for old in dest.glob('*.so'):
        old.unlink()
    for path in (prefix / 'lib').glob('lib*.so'):
        # Linker aliases duplicate OpenSSL/SQLite by several MB. Android uses
        # their real *_python.so SONAMEs, so only package the real files.
        if not path.is_symlink():
            shutil.copyfile(path, dest / path.name)
    for path in (prefix / 'lib/python3.13/lib-dynload').glob('*.so'):
        name = path.name.split('.')[0]
        shutil.copyfile(path, dest / f'libpython_ext_{name}.so')
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    triple = 'armv7a-linux-androideabi' if abi == 'armeabi-v7a' else HOSTS[abi]
    run([toolchain / f'{triple}{LOCK["android"]["min_api"]}-clang',
         '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
         '-Wl,-z,max-page-size=16384', '-Wl,--no-undefined',
         '-I' + str(prefix / 'include/python3.13'),
         MODULE / 'src/main/c/python_bridge.c', '-L' + str(prefix / 'lib'),
         '-lpython3.13', '-o', dest / 'libroutegram_python.so'])
    for path in dest.glob('*.so'):
        run([toolchain / 'llvm-strip', '--strip-unneeded', path])
    report = {}
    system_libraries = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libz.so'}
    for path in sorted(dest.glob('*.so')):
        headers = subprocess.check_output([str(toolchain / 'llvm-readelf'), '-lW', str(path)], text=True)
        alignments = [int(line.split()[-1], 16) for line in headers.splitlines()
                      if line.lstrip().startswith('LOAD ')]
        if not alignments or min(alignments) < 16384:
            raise RuntimeError(f'{abi}/{path.name}: not aligned for 16 KB pages')
        dynamic = subprocess.check_output([str(toolchain / 'llvm-readelf'), '-d', str(path)], text=True)
        needed = [line.split('[')[1].split(']')[0] for line in dynamic.splitlines() if '(NEEDED)' in line]
        missing = [name for name in needed if name not in system_libraries and not (dest / name).is_file()]
        if missing:
            raise RuntimeError(f'{abi}/{path.name}: missing native dependencies: {missing}')
        report[path.name] = {'bytes': path.stat().st_size, 'needed': needed,
                             'load_alignment': min(alignments),
                             'sha256': hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()}
    (OUT / f'{abi}-elf.json').write_text(json.dumps(report, indent=2) + '\n')


def package_python(prefixes, flowseal):
    assets = OUT / 'assets/routegram-python'
    assets.mkdir(parents=True, exist_ok=True)
    notices = OUT / 'assets/routegram-notices'
    notices.mkdir(parents=True, exist_ok=True)
    python_source = WORK / 'sources' / ('cpython-' + LOCK['python']['ref'])
    for path in (python_source / 'Modules').rglob('*'):
        if path.is_file() and path.name.upper().startswith(('LICENSE', 'COPYING')):
            name = str(path.relative_to(python_source)).replace('/', '_')
            shutil.copyfile(path, notices / name)
    shutil.copyfile(prefixes[0] / 'share/doc/xz/COPYING', notices / 'xz.txt')
    lib = prefixes[0] / 'lib/python3.13'
    excluded = {'test', 'tests', '__pycache__', 'site-packages', 'lib-dynload',
                'idlelib', 'tkinter', 'turtledemo', 'ensurepip', 'venv'}
    # encodings must load before the native-extension finder can import zlib.
    with zipfile.ZipFile(assets / 'stdlib.zip', 'w', zipfile.ZIP_STORED) as archive:
        for path in sorted(lib.rglob('*')):
            rel = path.relative_to(lib)
            if (path.is_file() and not (set(rel.parts) & excluded) and path.suffix == '.py'
                    and not path.name.startswith('_sysconfigdata_')):
                archive.write(path, rel.as_posix())
        for prefix in prefixes:
            for path in (prefix / 'lib/python3.13').glob('_sysconfigdata_*.py'):
                archive.write(path, path.name)
    with zipfile.ZipFile(assets / 'packages.zip', 'w', zipfile.ZIP_STORED) as archive:
        for path in sorted((flowseal / 'proxy').glob('*.py')):
            archive.write(path, 'proxy/' + path.name)
        archive.write(flowseal / 'LICENSE', 'licenses/Flowseal.txt')
        archive.write(MODULE / 'src/main/python/routegram_runtime.py', 'routegram_runtime.py')
        for wheel in LOCK['wheels']:
            downloaded = download(wheel['url'], wheel['sha256'])
            with zipfile.ZipFile(downloaded) as source:
                for entry in source.infolist():
                    if not entry.is_dir():
                        archive.writestr(entry.filename, source.read(entry))
    # certifi opens its data through an ordinary filesystem path. Extract the
    # small packages archive at startup; stdlib stays in its uncompressed zip.
    shutil.copyfile(WORK / 'sources' / ('cpython-' + LOCK['python']['ref']) / 'LICENSE',
                    assets / 'PYTHON-LICENSE.txt')
    shutil.copyfile(MODULE / 'upstream.json', assets / 'upstream.json')
    files = {path.name: hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
             for path in assets.iterdir() if path.is_file() and path.name != 'manifest.json'}
    (assets / 'manifest.json').write_text(json.dumps({'files': files}, sort_keys=True) + '\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--abi', choices=HOSTS, action='append')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    ndk = prepare_ndk()
    python = source_zip('python/cpython', LOCK['python'])
    flowseal = source_zip('Flowseal/tg-ws-proxy', LOCK['flowseal'])
    for abi in args.abi or LOCK['android']['abis']:
        prefix = build_python(python, HOSTS[abi], args.jobs)
        package_native(prefix, abi, ndk)
    prefixes = [python / 'cross-build' / host / 'prefix' for host in HOSTS.values()
                if (python / 'cross-build' / host / 'prefix/lib/libpython3.13.so').exists()]
    package_python(prefixes, flowseal)
    (OUT / 'build-info.json').write_text(json.dumps(LOCK, indent=2) + '\n')


if __name__ == '__main__':
    main()
