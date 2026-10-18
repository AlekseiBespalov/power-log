import json
import re
from pathlib import Path

FIXTURE = json.loads((Path(__file__).resolve().parents[1] / "fixtures/contract/presentation/activity.json").read_text())
RENDER_LATENCY_SECONDS = 1.0


class QualificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise QualificationError(message)


def stage(operation):
    try:
        operation()
        return {"result": "passed"}
    except (QualificationError, StopIteration) as error:
        return {"result": "failed", "reason": str(error) or "Required evidence absent"}


def qualify(case, acquired, publications, renders, lifecycle, frames):
    bike_expiry = acquired + (int(FIXTURE["bikeExpiresAtUs"]) - int(FIXTURE["bikeAcquiredUs"])) / 1_000_000
    heart_expiry = acquired + (int(FIXTURE["heartRateExpiresAtUs"]) - int(FIXTURE["heartAcquiredUs"])) / 1_000_000
    live = [row for row in publications if row["bike"] == "200" and row["heart"] == "150"]
    bike_update = [row for row in publications if row["bike"] == "—" and row["heart"] == "150"]
    heart_update = [row for row in publications if row["bike"] == "—" and row["heart"] == "—"]
    final_expiry = bike_expiry if case == "B" else heart_expiry

    def publication():
        require(live, "No available-reading publication")
        require(abs(live[-1]["staleDate"] - bike_expiry) < 0.001, "Initial staleDate must be supplied bike expiry")
        if case in ("A", "C"):
            require(bike_update, "No bike deadline update")
            require(bike_update[-1]["flag"], "Bike deadline must bypass throttle")
            require(
                abs(bike_update[-1]["now"] - bike_expiry) <= RENDER_LATENCY_SECONDS,
                "Bike publication missed deadline",
            )
            require(abs(bike_update[-1]["staleDate"] - heart_expiry) < 0.001, "HR must become supplied staleDate")
        if case == "A":
            require(heart_update, "No HR deadline update")
            require(heart_update[-1]["flag"], "HR deadline must bypass throttle")
            require(
                abs(heart_update[-1]["now"] - heart_expiry) <= RENDER_LATENCY_SECONDS,
                "HR publication missed deadline",
            )
            require(heart_update[-1]["staleDate"] == -1, "No future staleDate after both expiries")

    def suspension():
        if case == "A":
            require(lifecycle.get("unsuspendedAtHeartExpiry") is True, "Running app must remain unsuspended")
            return
        suspended = lifecycle.get("suspendedAt")
        require(lifecycle.get("method") == "os-background", "OS suspension must follow Home, without process signals")
        require(suspended is not None, "OS suspension was not observed")
        require(
            (acquired if case == "B" else bike_expiry) < suspended < final_expiry,
            "Suspension missed required interval",
        )
        require(not any(row["now"] > suspended for row in publications), "Suspended app published again")

    def eventual():
        if case == "A":
            require(
                any(row["bike"] == "—" and row["heart"] == "150" for row in renders),
                "Bike expiry never rendered",
            )
            require(any(row["bike"] == "—" and row["heart"] == "—" for row in renders), "HR expiry never rendered")
        else:
            require(
                any(row["flag"] and row["bike"] == "—" and row["heart"] == "—" for row in renders),
                "No system-stale render observed after OS suspension",
            )

    def expiry():
        for row in renders:
            require(row["now"] < bike_expiry or row["bike"] == "—", "Bike reading rendered after its expiry")
            require(row["now"] < heart_expiry or row["heart"] == "—", "Heart reading rendered after its expiry")
            require(
                not row["flag"] or (row["bike"] == "—" and row["heart"] == "—"),
                "System stale retained a live reading",
            )
        deadlines = [bike_expiry, heart_expiry] if case == "A" else [final_expiry]
        for deadline in deadlines:
            expected_heart = "150" if deadline == bike_expiry and case == "A" else "—"
            require(
                any(
                    deadline <= row["now"] <= deadline + RENDER_LATENCY_SECONDS
                    and row["bike"] == "—" and row["heart"] == expected_heart
                    and (case == "A" or row["flag"])
                    for row in renders
                ),
                f"Expiry {deadline} did not render within {RENDER_LATENCY_SECONDS}s",
            )

    def visible():
        deadlines = [bike_expiry, heart_expiry] if case == "A" else [final_expiry]
        for deadline in deadlines:
            matching = [frame for frame in frames if abs(frame["deadline"] - deadline) < 0.001]
            require(matching, "Activity screenshot missing at expiry")
            frame = matching[-1]
            require(
                frame["surface"] == "dynamicIsland" and frame["visible"],
                "Screenshot must show the Activity itself",
            )
            require(
                deadline <= frame["capturedAt"] <= deadline + RENDER_LATENCY_SECONDS,
                "Activity screenshot arrived too late",
            )
            panel_text = " ".join(frame["panelText"]).lower()
            require(not re.search(r"\b200\s*w\b", panel_text), "Screenshot retains expired bike value")
            if case == "A":
                require(re.search(r"(?:—|–|-|_)\s*w\b", panel_text), "Screenshot must show unavailable bike entry")
                if deadline == bike_expiry:
                    require(
                        re.search(r"\b150\s*bpm\b", panel_text),
                        "Screenshot must retain available HR at bike deadline",
                    )
                else:
                    require(
                        re.search(r"(?:—|–|-|_)\s*bpm\b", panel_text),
                        "Screenshot must show unavailable HR entry",
                    )
                    require(not re.search(r"\b150\s*bpm\b", panel_text), "Screenshot retains expired HR value")
            else:
                require(not re.search(r"\b150\s*bpm\b", panel_text), "System-stale screenshot retains a live HR value")
                require("open power log" in panel_text, "Screenshot must show supplied system-stale status")

    results = {
        "publication": stage(publication), "suspension": stage(suspension), "eventualRender": stage(eventual),
        "expiry": stage(expiry), "visibleActivity": stage(visible),
    }
    results["qualification"] = "passed" if all(value["result"] == "passed" for value in results.values()) else "failed"
    return results
