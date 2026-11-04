#!/usr/bin/env python3
"""Retained host checks. Unsupported Windows directory sync is NOT production success.

The production source is never edited. A separate, explicitly generated host-test
class models sync acknowledgement/failure so protocol checks can run independently
of host durability support. Android and power-loss acceptance always remain NOT_RUN.
"""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import subprocess
from datetime import datetime, timezone


SYNC_METHOD = 'static void forceDirectory(Path path)throws IOException {try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}}'
MODEL_METHOD = 'static void forceDirectory(Path path)throws IOException {HostDirectorySyncModel.force(path);}'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--exercise-synthetic-protocol', action='store_true',
                        help='Also exercise the explicit sync model on capable hosts; never changes capability classification')
    args = parser.parse_args()
    project, out = args.project.resolve(), args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    src = project / 'android/app/src/main/java/org/wangshu/ime'
    here = Path(__file__).resolve().parent
    stub = out / 'stubs/android/content'
    stub.mkdir(parents=True)
    (stub / 'Context.java').write_text('package android.content; public abstract class Context {public Context getApplicationContext(){return this;} public abstract SharedPreferences getSharedPreferences(String name,int mode);public abstract java.io.File getFilesDir();public abstract java.io.File getDataDir();}', encoding='utf-8')
    (stub / 'SharedPreferences.java').write_text('''package android.content;import java.util.*;public interface SharedPreferences {Map<String,?> getAll();String getString(String k,String d);Set<String> getStringSet(String k,Set<String>d);int getInt(String k,int d);long getLong(String k,long d);float getFloat(String k,float d);boolean getBoolean(String k,boolean d);boolean contains(String k);Editor edit();void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);interface OnSharedPreferenceChangeListener{void onSharedPreferenceChanged(SharedPreferences p,String k);}interface Editor{Editor putString(String k,String v);Editor putStringSet(String k,Set<String>v);Editor putInt(String k,int v);Editor putLong(String k,long v);Editor putFloat(String k,float v);Editor putBoolean(String k,boolean v);Editor remove(String k);Editor clear();boolean commit();void apply();}}''', encoding='utf-8')
    files = [src / name for name in ('SettingsBackup.java', 'AppPreferences.java', 'SettingsRecoveryFiles.java', 'MathSidebar.java')]
    files += [here / name for name in ('SettingsBackupChecks.java', 'AppPreferencesChecks.java', 'SettingsRecoveryChecks.java', 'SettingsHostFilesystemProbe.java', 'SettingsDirectorySyncFailureChecks.java')]
    files += sorted(stub.glob('*.java'))
    tracked = files + [here / 'HostDirectorySyncModel.java']
    result = {'scope': 'Host-only production codec, adapter and recovery files with synthetic SharedPreferences. Separate labelled directory-sync model when needed. No Android acceptance.',
              'Android': 'NOT_RUN', 'power_loss_durability': 'NOT_RUN',
              'started_utc': datetime.now(timezone.utc).isoformat(),
              'runner_sha256': sha(Path(__file__)), 'host_platform': platform.platform(),
              'sources': {str(p): sha(p) for p in tracked}, 'commands': [],
              'host_directory_sync': 'NOT_RUN', 'production_host_recovery': 'NOT_RUN',
              'synthetic_protocol': 'NOT_RUN', 'classification': 'FAILED'}

    def run(name, command):
        command = [str(p) for p in command]
        completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace')
        text = completed.stdout + completed.stderr
        (out / (name + '.txt')).write_text(text, encoding='utf-8')
        print(text, end='', flush=True)
        result[name] = completed.returncode
        result['commands'].append({'name': name, 'command': command, 'returncode': completed.returncode})
        if completed.returncode:
            raise RuntimeError(name + ' failed: ' + str(completed.returncode))
        return text

    def compile_lane(name, sources):
        classes = out / name
        classes.mkdir()
        run(name + '-compile', ['java', '-Dfile.encoding=UTF-8', '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                               '-source', '17', '-target', '17', '-encoding', 'UTF-8', '-d', classes, *sources])
        return classes

    def java(name, classes, main_class, *arguments, sync_model=None):
        temporary = out / (name + '-temporary')
        temporary.mkdir()
        command = ['java', '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + str(temporary)]
        if sync_model is not None:
            command += ['-Dwangshu.hostTest.directorySync=' + sync_model]
        return run(name, [*command, '-cp', classes, 'org.wangshu.ime.' + main_class, *arguments])

    try:
        result['java_version'] = run('java-version', ['java', '-Dfile.encoding=UTF-8', '--version'])
        classes = compile_lane('classes', files)
        java('checks', classes, 'SettingsBackupChecks')
        probe = java('filesystem-probe', classes, 'SettingsHostFilesystemProbe', out / 'filesystem-probe-fixtures')
        if 'HOST_DIRECTORY_SYNC=AVAILABLE\n' in probe:
            result['host_directory_sync'] = 'AVAILABLE_API_CALL_RETURNED'
            java('adapter', classes, 'AppPreferencesChecks')
            recovery = java('recovery', classes, 'SettingsRecoveryChecks', out / 'fixtures')
            result['production_host_recovery'] = 'PASSED_HOST_ONLY'
        elif 'HOST_DIRECTORY_SYNC=WINDOWS_DIRECTORY_SYNC_ACCESS_DENIED\n' in probe:
            result['host_directory_sync'] = 'WINDOWS_DIRECTORY_SYNC_ACCESS_DENIED'
            result['production_host_recovery'] = 'UNAVAILABLE_FAIL_CLOSED'
            java('production-fail-closed', classes, 'SettingsDirectorySyncFailureChecks',
                 out / 'production-failure-fixtures', 'UNMODIFIED production sync on this unsupported host')
        else:
            raise RuntimeError('missing or unrecognized filesystem capability result')

        # The only substitution is retained under output, hash-bound and never compiled into Android.
        original = src / 'SettingsRecoveryFiles.java'
        content = original.read_text(encoding='utf-8')
        if content.count(SYNC_METHOD) != 1:
            raise RuntimeError('production sync method changed; review host model rather than silently patching it')
        generated = out / 'HOST-TEST-ONLY-SYNTHETIC-SOURCE' / 'SettingsRecoveryFiles.java'
        generated.parent.mkdir()
        generated.write_text('// HOST TEST ONLY: directory sync is a synthetic acknowledgement/failure model, NOT durability.\n' + content.replace(SYNC_METHOD, MODEL_METHOD), encoding='utf-8')
        result['synthetic_source'] = {'path': str(generated), 'sha256': sha(generated),
                                      'production_sha256': sha(original),
                                      'substitution': 'Only forceDirectory delegates to HostDirectorySyncModel; original source unchanged; not an Android source set.'}
        synthetic_files = [generated if p == original else p for p in files] + [here / 'HostDirectorySyncModel.java']
        synthetic_classes = compile_lane('synthetic-classes', synthetic_files)
        java('injected-fail-closed', synthetic_classes, 'SettingsDirectorySyncFailureChecks',
             out / 'injected-failure-fixtures', 'SYNTHETIC directory-sync failure injection', sync_model='fail')
        if result['host_directory_sync'] == 'WINDOWS_DIRECTORY_SYNC_ACCESS_DENIED' or args.exercise_synthetic_protocol:
            print('SYNTHETIC DIRECTORY-SYNC PROTOCOL LANE: no real directory sync, no Android durability claim.', flush=True)
            java('synthetic-adapter', synthetic_classes, 'AppPreferencesChecks', sync_model='acknowledge')
            recovery = java('synthetic-recovery', synthetic_classes, 'SettingsRecoveryChecks',
                            out / 'synthetic-fixtures', sync_model='acknowledge')
            result['synthetic_protocol'] = 'PASSED_SYNTHETIC_DIRECTORY_SYNC_ONLY'
        result['host_symlink_rejection'] = 'NOT_RUN_HOST_OPERATION_FAILED' if 'HOST_SYMLINK=UNAVAILABLE' in recovery else 'PASSED_HOST_ONLY'
        result['sources_unchanged'] = all(sha(Path(p)) == digest for p, digest in result['sources'].items())
        if not result['sources_unchanged']:
            raise RuntimeError('source changed during test')
        result['classification'] = ('HOST_CHECKS_PASSED_WITH_UNAVAILABLE_DIRECTORY_SYNC' if
                                    result['host_directory_sync'] == 'WINDOWS_DIRECTORY_SYNC_ACCESS_DENIED' else 'HOST_CHECKS_PASSED')
        print(result['classification'] + '; Android/power-loss durability NOT_RUN.', flush=True)
    except (OSError, RuntimeError) as failure:
        result['error'] = str(failure)
        print('FAIL: ' + str(failure), flush=True)
    finally:
        result['finished_utc'] = datetime.now(timezone.utc).isoformat()
        (out / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return 1 if result['classification'] == 'FAILED' else 0


if __name__ == '__main__':
    raise SystemExit(main())
