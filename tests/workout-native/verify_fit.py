"""Independent official Garmin decoder checks; only synthetic test workouts are used."""
import json
import math
import pathlib
import sys
from garmin_fit_sdk import Decoder, Stream

root = pathlib.Path(sys.argv[1])
def decode(name):
    path = root / name
    decoder = Decoder(Stream.from_file(str(path)))
    assert decoder.check_integrity(), f"header/file CRC integrity: {name}"
    messages, errors = Decoder(Stream.from_file(str(path))).read()
    assert not errors, errors
    return messages

m = decode("synthetic.fit")
assert int.from_bytes((root / "synthetic.fit").read_bytes()[2:4], "little") / 1000 == 21.214
assert m["file_id_mesgs"][0]["type"] == "activity"
assert m["file_id_mesgs"][0]["manufacturer"] == "development"
s = m["session_mesgs"][0]
assert s["sport"] == "cycling" and s["sub_sport"] == "e_bike_fitness", s
assert s["total_timer_time"] == 10 and s["total_elapsed_time"] == 14
assert s["avg_power"] == 175 and s["max_power"] == 400
assert s["total_work"] == 700 and s["total_calories"] == 26
assert "avg_speed" not in s, "Partial coverage is not whole-session average speed"
assert s["num_laps"] == 2 and len(m["lap_mesgs"]) == 2
assert math.isclose(sum(l["total_timer_time"] for l in m["lap_mesgs"]), 10)
assert math.isclose(sum(l["total_distance"] for l in m["lap_mesgs"]), s["total_distance"], abs_tol=.02)
assert m["activity_mesgs"][0]["num_sessions"] == 1
r = m["record_mesgs"]
origin = r[0]["timestamp"]
power = {int((e["timestamp"] - origin).total_seconds()): e["power"] for e in r if "power" in e}
assert power == {0:150,2:100,7:0,8:200,12:400,13:200}, power
assert not any(e.get("power") == 9999 for e in r), "Motor watts cannot become athlete power"
assert not any("temperature" in e for e in r), "Motor/controller temperature is not ambient temperature"
assert not any(int((e["timestamp"] - origin).total_seconds()) in [3,4,5,6,10,11] for e in r), "No stale records filling gaps"
assert all(r[i]["timestamp"] <= r[i+1]["timestamp"] for i in range(len(r)-1))
assert any(e.get("position_long",0) != 0 for e in r)
assert all(e.get("enhanced_speed", 5) == 5 for e in r), "Unknown speedRaw was not exported"
timers = [(int((e["timestamp"]-origin).total_seconds()), e["event_type"]) for e in m["event_mesgs"]]
assert timers == [(0,"start"),(3,"stop_all"),(7,"start"),(14,"stop_all")], timers

empty = decode("empty.fit")
assert not empty.get("record_mesgs"), "No fake record in empty workout"
assert "avg_power" not in empty["session_mesgs"][0]
assert "total_distance" not in empty["session_mesgs"][0]
assert len(empty["lap_mesgs"]) == 1

typed = decode("typed-analysis.fit")
corrected = decode("typed-corrected.fit")
assert math.isclose(typed["session_mesgs"][0]["total_timer_time"], 2.125, abs_tol=0.001)
assert typed["session_mesgs"][0]["avg_power"] == 140
assert corrected["session_mesgs"][0]["avg_power"] == 120
assert math.isclose(typed["session_mesgs"][0]["total_distance"], 2.22, abs_tol=0.01)
assert typed["session_mesgs"][0]["total_distance"] == corrected["session_mesgs"][0]["total_distance"]

corrupt = bytearray((root / "synthetic.fit").read_bytes())
corrupt[len(corrupt)//2] ^= 1
bad = root / "corrupt.fit"
bad.write_bytes(corrupt)
assert not Decoder(Stream.from_file(str(bad))).check_integrity(), "Independent decoder detects corruption"
bad.unlink()
print("Official Garmin FIT SDK: CRC, messages, timing, laps, fields, units, missing values and corruption checks passed.")
