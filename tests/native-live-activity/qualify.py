import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import time
from evidence import qualify, RENDER_LATENCY_SECONDS

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "test-results/la/simulator-os"
REPORT.mkdir(parents=True, exist_ok=True)
ENV = {
    **os.environ,
    "DEVELOPER_DIR": "/Applications/Xcode.app/Contents/Developer",
    "PATH": "/opt/homebrew/opt/node@24/bin:" + os.environ["PATH"],
    "BUNDLE_PATH": str(Path.home() / ".cache/power-log/bundle"),
}


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, env=ENV, text=True, stderr=subprocess.STDOUT).strip()


def sim(*args):
    return run("/usr/bin/xcrun", "simctl", *args)


def rows(path, kind):
    pattern = rf"{kind} sequence=(\d+) now=([\d.]+) staleDate=([\d.-]+) (?:deadline|isStale)=(true|false|0|1) bike=(.*?) heart=(.*?)(?: status=(.*))?$"
    values = []
    for line in path.read_text().splitlines():
        match = re.search(pattern, line)
        if match:
            sequence, now, stale, flag, bike, heart, status = match.groups()
            values.append(dict(sequence=int(sequence), now=float(now), staleDate=float(stale),
                               flag=flag in ("true", "1"), bike=bike, heart=heart, status=status))
    return values


def execute_logged(path, *args):
    with path.open("w") as output:
        return subprocess.run(args, cwd=ROOT, env=ENV, stdout=output, stderr=subprocess.STDOUT).returncode


def test_targets(configuration):
    return [configuration["ActivityQualification"]]


def main():
    result = {"cases": {}, "qualification": "failed", "renderLatencySeconds": RENDER_LATENCY_SECONDS}
    udid = None
    try:
        runtime = next(row for row in json.loads(sim("list", "runtimes", "--json"))["runtimes"]
                       if row["isAvailable"] and "iOS" in row["name"] and int(row["version"].split(".")[0]) >= 26)
        device = next(row for row in json.loads(sim("list", "devicetypes", "--json"))["devicetypes"]
                      if row["name"] == "iPhone 17 Pro Max")
        result.update(runtime=runtime["name"], device=device["name"])
        udid = sim("create", "Power Log Activity OS qualification", device["identifier"], runtime["identifier"])
        sim("boot", udid)
        sim("bootstatus", udid, "-b")
        app = REPORT / "bundle/PowerLog.app"
        app.parent.mkdir(exist_ok=True)
        shutil.copytree(ROOT / "ios/build/Build/Products/Debug-iphonesimulator/PowerLog.app", app, dirs_exist_ok=True)
        run("/usr/bin/codesign", "--force", "--deep", "--sign", "-", str(app))
        run("/opt/homebrew/opt/node@24/bin/npm", "run", "test:ios-bundle", "--", str(app))
        run("/usr/bin/codesign", "--verify", "--deep", "--strict", str(app))
        bundle = run("/usr/bin/plutil", "-extract", "CFBundleIdentifier", "raw", "-o", "-", str(app / "Info.plist"))
        project = REPORT / "project"
        run("bundle", "exec", "ruby", "tests/native-live-activity/make-project.rb", str(project))
        derived = REPORT / "ui-build"
        status = execute_logged(REPORT / "ui-build.log", "/usr/bin/xcrun", "xcodebuild", "build-for-testing",
                                "-project", str(project / "ActivityQualification.xcodeproj"),
                                "-scheme", "ActivityQualification", "-destination", f"id={udid}",
                                "-derivedDataPath", str(derived), "CODE_SIGNING_ALLOWED=NO")
        assert status == 0, "Native UI test build failed; see ui-build.log"
        template = next((derived / "Build/Products").glob("*.xctestrun"))
        for runner in (derived / "Build/Products").rglob("*.app"):
            run("/usr/bin/codesign", "--force", "--deep", "--sign", "-", str(runner))
        for case in ("F", "A", "B", "C"):
            sim("install", udid, str(app))
            log_path = REPORT / f"case-{case}.log"
            ui_log = REPORT / f"case-{case}-ui.log"
            acquired = time.time() + 45
            with template.open("rb") as input_file:
                configuration = plistlib.load(input_file)
            for target in test_targets(configuration):
                target.setdefault("EnvironmentVariables", {}).update(
                    POWER_LOG_ACTIVITY_CASE=case, POWER_LOG_ACTIVITY_ACQUIRED=str(acquired),
                    POWER_LOG_ACTIVITY_BUNDLE=bundle,
                )
            test_run = template.parent / f"case-{case}.xctestrun"
            with test_run.open("wb") as output:
                plistlib.dump(configuration, output)
            xcresult = REPORT / f"case-{case}-{int(time.time())}.xcresult"
            with log_path.open("w") as output:
                stream = subprocess.Popen(
                    ["/usr/bin/xcrun", "simctl", "spawn", udid, "log", "stream", "--level", "debug",
                     "--style", "compact", "--predicate", 'subsystem == "app.powerlog.activity.qualification"'],
                    env=ENV, stdout=output, stderr=subprocess.STDOUT,
                )
                try:
                    status = execute_logged(ui_log, "/usr/bin/xcrun", "xcodebuild", "test-without-building",
                                            "-xctestrun", str(test_run), "-destination", f"id={udid}",
                                            "-parallel-testing-enabled", "NO", "-resultBundlePath", str(xcresult))
                    evidence_lines = [line.split("ACTIVITY_EVIDENCE ", 1)[1]
                                      for line in ui_log.read_text().splitlines() if "ACTIVITY_EVIDENCE " in line]
                    if not evidence_lines:
                        result["cases"][case] = {"qualification": "failed", "failure": "Native UI driver returned no evidence",
                                                 "nativeTestExit": status}
                        continue
                    evidence = json.loads(evidence_lines[-1])
                    publications = [row for row in rows(log_path, "PUBLISH") if row["now"] <= evidence["observedUntil"]]
                    renders = [row for row in rows(log_path, "RENDER") if row["now"] <= evidence["observedUntil"]]
                    stages = qualify("A" if case == "F" else case, evidence["acquired"], publications, renders,
                                     evidence["lifecycle"], evidence["frames"])
                    if case == "F":
                        foreground = evidence["lifecycle"].get("foreground") is True and all(
                            row["foreground"] for row in evidence["lifecycle"]["deadlineStates"])
                        stages = {"publication": stages["publication"],
                                  "foreground": {"result": "passed" if foreground else "failed"},
                                  "qualification": "passed" if foreground and stages["publication"]["result"] == "passed" else "failed"}
                    result["cases"][case] = {**evidence, **stages, "publications": publications,
                                             "renders": renders, "nativeTestExit": status}
                    attachments = REPORT / (xcresult.stem + "-screenshots")
                    attachments.mkdir(exist_ok=True)
                    result["cases"][case]["screenshots"] = str(attachments.relative_to(ROOT))
                    if status != 0:
                        result["cases"][case]["nativeDriver"] = {"result": "failed", "exit": status}
                        result["cases"][case]["qualification"] = "failed"
                    run("/usr/bin/xcrun", "xcresulttool", "export", "attachments", "--path", str(xcresult),
                        "--output-path", str(attachments))
                finally:
                    stream.terminate()
                    stream.wait(timeout=10)
                    sim("uninstall", udid, bundle)
        if len(result["cases"]) == 4 and all(row["qualification"] == "passed" for row in result["cases"].values()):
            result["qualification"] = "passed"
    except (AssertionError, StopIteration, KeyError, ValueError, OSError, subprocess.CalledProcessError) as error:
        result["failure"] = str(error) or "Required simulator capability absent"
    finally:
        (REPORT / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        if udid:
            sim("shutdown", udid)
            sim("delete", udid)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
