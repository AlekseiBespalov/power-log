from pathlib import Path
import csv
import io
import math
import sys
import zipfile

from garmin_fit_sdk import Decoder, Stream


root = Path(sys.argv[1])
path = str(root / 'synthetic.fit')
assert Decoder(Stream.from_file(path)).check_integrity()
messages, errors = Decoder(Stream.from_file(path)).read()
assert not errors, errors
session = messages['session_mesgs'][0]
laps = messages['lap_mesgs']
records = messages['record_mesgs']
assert session['sport'] == 'cycling' and session['sub_sport'] == 'e_bike_fitness'
assert session['total_elapsed_time'] == 300 and session['total_timer_time'] == 290
assert session['num_laps'] == 2 and len(laps) == 2
assert sum(lap['total_timer_time'] for lap in laps) == 290
assert abs(sum(lap['total_distance'] for lap in laps) - session['total_distance']) < .02
assert len(records) == 290
seconds = [(record['timestamp'] - session['start_time']).total_seconds() for record in records]
assert seconds == list(range(100)) + list(range(110, 300)), seconds
assert all(99 <= r['power'] <= 221 for r in records)
assert all('heart_rate' not in r for r in records)
assert records[-1]['distance'] == session['total_distance']
with zipfile.ZipFile(root / 'synthetic.zip') as archive:
    rows = list(csv.DictReader(io.StringIO(archive.read('telemetry.csv').decode())))
    assert len(rows) == 2400
    active = [row for row in rows if not 100 <= float(row['elapsedSeconds']) < 110]
    assert len(active) == 2320
    assert session['max_power'] == math.floor(max(float(row['humanPowerW']) for row in active) + .5)
    bins = {}
    for row in active:
        second = math.floor(float(row['elapsedSeconds']))
        bins.setdefault(second, []).append(float(row['humanPowerW']))
    expected = {second: math.floor(sum(values) / len(values) + .5) for second, values in bins.items()}
    assert {second: record['power'] for second, record in zip(seconds, records)} == expected
print('Garmin decoder: valid FIT; paused-record exclusion, active power maxima/bins, two laps, distance and 2,400 ZIP originals verified')
