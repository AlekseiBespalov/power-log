import unittest
import json
import os
import subprocess
import sys
from pathlib import Path
from evidence import qualify


class QualificationTests(unittest.TestCase):
    def optimized_saved_failures(self, arguments, environment):
        fixture = Path(__file__).resolve().parents[1] / "fixtures/live-activity-qualification.json"
        evaluation = """
import json
import sys
from evidence import qualify

if sys.flags.optimize < 1:
    raise RuntimeError("Optimization regression must run with Python optimization enabled")
cases = json.load(open(sys.argv[1]))["cases"]
results = {case: qualify(case, **evidence) for case, evidence in cases.items()}
print(json.dumps(results))
sys.exit(1 if any(result["qualification"] == "failed" for result in results.values()) else 0)
"""
        result = subprocess.run(
            [sys.executable, *arguments, "-c", evaluation, str(fixture)],
            cwd=Path(__file__).resolve().parent, env=environment, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        evidence = json.loads(result.stdout)
        reasons = {
            "A": "Screenshot must show unavailable bike entry",
            "B": "Screenshot retains expired bike value",
            "C": "System-stale screenshot retains a live HR value",
        }
        for case, reason in reasons.items():
            self.assertEqual(evidence[case]["qualification"], "failed")
            self.assertEqual(evidence[case]["visibleActivity"], {"result": "failed", "reason": reason})

    def test_optimized_flag_preserves_saved_qualification_failures(self):
        environment = os.environ.copy()
        environment.pop("PYTHONOPTIMIZE", None)
        self.optimized_saved_failures(["-O"], environment)

    def test_optimized_environment_preserves_saved_qualification_failures(self):
        self.optimized_saved_failures([], {**os.environ, "PYTHONOPTIMIZE": "1"})

    def evidence(self, case="A"):
        publication = [dict(sequence=1, now=105, staleDate=106, flag=False, bike="200", heart="150")]
        if case != "B":
            publication.append(dict(sequence=2, now=106.01, staleDate=115, flag=True, bike="—", heart="150"))
        if case == "A":
            publication.append(dict(sequence=3, now=115.01, staleDate=-1, flag=True, bike="—", heart="—"))
        deadlines = [106, 115] if case == "A" else [106 if case == "B" else 115]
        renders = [dict(now=deadline + 0.1, flag=case != "A", bike="—",
                        heart="150" if case == "A" and deadline == 106 else "—") for deadline in deadlines]
        frames = [dict(deadline=deadline, capturedAt=deadline + 0.2, surface="dynamicIsland", visible=True,
                       bikeText=["— W"], heartText=[row["heart"] + " bpm"],
                       panelText=["Bike unavailable", "— W", row["heart"] + " bpm"])
                  for deadline, row in zip(deadlines, renders)]
        if case != "A":
            for frame in frames:
                frame.update(bikeText=[], heartText=[], panelText=["Open Power Log"])
        lifecycle = dict(
            unsuspendedAtHeartExpiry=True, method="os-background", suspendedAt=105.5 if case == "B" else 110,
        )
        return publication, renders, lifecycle, frames

    def test_recorded_evidence_shifted_one_hour_fails_expiry_qualification(self):
        fixture = json.loads((Path(__file__).resolve().parents[1] / "fixtures/live-activity-render.json").read_text())
        for row in fixture["renders"]:
            row["now"] += 3600
        result = qualify("A", 100, fixture["publications"], fixture["renders"],
                         {"unsuspendedAtHeartExpiry": True}, [])
        self.assertEqual(result["publication"]["result"], "passed")
        self.assertEqual(result["eventualRender"]["result"], "passed")
        self.assertEqual(result["expiry"]["result"], "failed")

    def test_on_time_evidence_qualifies_all_cases(self):
        for case in ("A", "B", "C"):
            self.assertEqual(qualify(case, 100, *self.evidence(case))["qualification"], "passed")

    def test_one_hour_late_render_is_eventual_but_never_expiry_qualified(self):
        values = self.evidence()
        for row in values[1]:
            row["now"] += 3600
        result = qualify("A", 100, *values)
        self.assertEqual(result["publication"]["result"], "passed")
        self.assertEqual(result["eventualRender"]["result"], "passed")
        self.assertEqual(result["expiry"]["result"], "failed")
        self.assertEqual(result["qualification"], "failed")

    def test_expired_reading_fails_even_with_other_on_time_renders(self):
        values = self.evidence()
        values[1].append(dict(now=116, flag=False, bike="—", heart="150"))
        self.assertEqual(qualify("A", 100, *values)["expiry"]["result"], "failed")

    def test_delayed_unavailable_render_fails_timing(self):
        values = self.evidence()
        values[1][-1]["now"] = 116.01
        self.assertEqual(qualify("A", 100, *values)["expiry"]["result"], "failed")

    def test_foreground_sigstop_does_not_qualify_os_suspension(self):
        values = self.evidence("B")
        values[2]["method"] = "SIGSTOP"
        self.assertEqual(qualify("B", 100, *values)["suspension"]["result"], "failed")

    def test_launcher_screenshot_does_not_qualify_visible_activity(self):
        values = self.evidence()
        values[3][0]["visible"] = False
        self.assertEqual(qualify("A", 100, *values)["visibleActivity"]["result"], "failed")

    def test_expired_visible_value_fails_even_with_on_time_render_logs(self):
        values = self.evidence()
        values[3][-1]["panelText"] = ["Bike unavailable", "— W", "150 bpm"]
        self.assertEqual(qualify("A", 100, *values)["visibleActivity"]["result"], "failed")

    def test_missing_system_stale_render_is_failure(self):
        values = self.evidence("C")
        values[1].clear()
        result = qualify("C", 100, *values)
        self.assertEqual(result["eventualRender"]["result"], "failed")
        self.assertEqual(result["expiry"]["result"], "failed")

    def test_suspension_after_expiry_is_not_the_requested_case(self):
        values = self.evidence("C")
        values[2]["suspendedAt"] = 116
        self.assertEqual(qualify("C", 100, *values)["suspension"]["result"], "failed")


if __name__ == "__main__":
    unittest.main()
