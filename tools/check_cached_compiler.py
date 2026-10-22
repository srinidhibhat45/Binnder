"""Local fallback: compile sources with cached Android/Kotlin dependencies, without Gradle sockets.
This checks Kotlin/Compose compilation; it does not package an APK or replace Gradle/lint/device tests.
"""
from pathlib import Path
import os, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
out = root / 'app/build/cached-check'
out.mkdir(parents=True, exist_ok=True)

def version_key(path):
    return tuple(int(p) if p.isdigit() else -1 for p in path.name.replace('-', '.').split('.'))

classpath = []
for group in cache.iterdir():
    if not (group.name.startswith('androidx.') or group.name in ('org.jetbrains.kotlin', 'org.jetbrains.kotlinx', 'org.jetbrains', 'com.google.guava', 'com.google.code.findbugs', 'junit', 'org.hamcrest')):
        continue
    for artifact in group.iterdir():
        versions = sorted(artifact.iterdir(), key=version_key, reverse=True)
        for version in versions:
            archives = list(version.glob('*/*.aar')) + list(version.glob('*/*.jar'))
            if not archives:
                continue
            for archive in archives:
                if archive.suffix == '.aar':
                    dest = out / f'{group.name}-{artifact.name}-{version.name}.jar'
                    with zipfile.ZipFile(archive) as z:
                        if 'classes.jar' in z.namelist():
                            dest.write_bytes(z.read('classes.jar'))
                            classpath.append(str(dest))
                else:
                    classpath.append(str(archive))
            break

sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
classpath.append(str(sdk / 'platforms/android-36/android.jar'))
compiler = next((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').glob('*/*.jar'))
plugin = next((cache/'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.2.21').glob('*/*.jar'))
stub = out / 'BuildConfig.kt'
stub.write_text('package com.binnder.app\nobject BuildConfig { const val VERSION_NAME = "0.1.2"; const val UPDATE_REPOSITORY="" }\n')
sources = [str(p) for p in (root/'app/src/main/java').rglob('*.kt')]+[str(stub)]
compiler_cp = ':'.join([str(compiler)]+classpath)
test_sources = [str(p) for p in (root/'app/src/test/java').rglob('*.kt')]
result = subprocess.run(['java', '-Xmx3g', '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-Xplugin='+str(plugin),
    '-classpath', ':'.join(classpath), '-d', str(out/'classes')]+sources+test_sources)
if result.returncode:
    raise SystemExit(result.returncode)
raise SystemExit(subprocess.run(['java','-cp',str(out/'classes')+':'+':'.join(classpath),
    'org.junit.runner.JUnitCore','com.binnder.app.ReviewPolicyTest','com.binnder.app.SafeDiskTest','com.binnder.app.ScanPolicyTest','com.binnder.app.UpdatePolicyTest']).returncode)
