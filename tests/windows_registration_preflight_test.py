"""Exercise the real batch entry points with isolated, non-executable fixtures."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new directory on E: for fixtures")
    args = parser.parse_args()
    output = args.output.resolve()
    if os.name != "nt" or output.drive.upper() != "E:":
        parser.error("this check requires Windows and an E: output directory")

    cmd = Path(os.environ["SystemRoot"]) / "System32" / "cmd.exe"
    scripts = Path(__file__).resolve().parents[1] / "windows"
    output.mkdir()
    package = output / "package %WANGSHU_UNDEFINED% !"
    package.mkdir()
    for name in ("install.cmd", "uninstall.cmd", "registration.cmd"):
        shutil.copyfile(scripts / name, package / name)
    fake_system = output / "fake Windows"
    for directory in ("System32", "Sysnative", "SysWOW64"):
        registrar = fake_system / directory / "regsvr32.exe"
        registrar.parent.mkdir(parents=True, exist_ok=True)
        registrar.write_bytes(b"fixture: not executable")
    for arch in ("x64", "x86"):
        (package / f"wangshu-ime-{arch}.dll").write_bytes(b"fixture: not a DLL")

    def check(script, arguments, arch, native_arch, expected_code, expected_lines):
        env = os.environ | {
            "WANGSHU_FIXTURE_SYSTEM": str(fake_system),
            "TEMP": str(output),
            "TMP": str(output),
        }
        driver = package / "run-fixture.cmd"
        driver.write_text(
            '@echo off\n'
            'setlocal DisableDelayedExpansion\n'
            'if not defined WANGSHU_FIXTURE_SYSTEM exit /b 99\n'
            'set "SystemRoot=%WANGSHU_FIXTURE_SYSTEM%"\n'
            f'set "PROCESSOR_ARCHITECTURE={arch}"\n'
            f'set "PROCESSOR_ARCHITEW6432={native_arch}"\n'
            f'".\\{script}" {" ".join(arguments)}\n',
            encoding="ascii", newline="\r\n",
        )
        call = [str(cmd), "/d", "/c", driver.name]
        result = subprocess.run(call, cwd=package, env=env, capture_output=True,
                                text=True, errors="replace", timeout=10)
        output_text = result.stdout + result.stderr
        assert result.returncode == expected_code, (call, result.returncode, output_text)
        for line in expected_lines:
            assert line in output_text, (call, line, output_text)
        print(f"PASS {arch}/{native_arch or 'native'} {script} {arguments}: {expected_lines[0]}")

    for script in ("install.cmd", "uninstall.cmd"):
        for arch, native_arch, pair in (("AMD64", "", "x64+x86"), ("x86", "AMD64", "x64+x86"),
                                        ("x86", "", "x86")):
            check(script, ["--preflight"], arch, native_arch, 0,
                  [f"passed for {pair}.", "Registration was not run."])
        check(script, ["--preflight"], "x86", "ARM64", 2, ["Supported Windows hosts"])
        check(script, ["--unknown"], "AMD64", "", 2, ["Usage:"])

    missing_wow_registrar = fake_system / "Sysnative" / "regsvr32.exe"
    missing_wow_registrar.rename(missing_wow_registrar.with_suffix(".fixture"))
    check("install.cmd", ["--preflight"], "x86", "AMD64", 2,
          ["Sysnative", "before any registrar was run"])

    x86_dll = package / "wangshu-ime-x86.dll"
    x86_dll.rename(x86_dll.with_suffix(".fixture"))
    for script in ("install.cmd", "uninstall.cmd"):
        check(script, [], "AMD64", "", 2, ["wangshu-ime-x86.dll", "before any registrar was run"])
    x86_dll.mkdir()
    check("install.cmd", ["--preflight"], "AMD64", "", 2,
          ["wangshu-ime-x86.dll", "before any registrar was run"])
    (package / "wangshu-ime-x64.dll").write_bytes(b"")
    check("install.cmd", ["--preflight"], "AMD64", "", 2,
          ["wangshu-ime-x64.dll", "before any registrar was run"])


if __name__ == "__main__":
    main()
