from pathlib import Path
import csv
import io
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
assert all(99 <= r['power'] <= 221 for r in records)
assert all('heart_rate' not in r for r in records)
assert records[-1]['distance'] == session['total_distance']
with zipfile.ZipFile(root / 'synthetic.zip') as archive:
    rows = list(csv.DictReader(io.StringIO(archive.read('telemetry.csv').decode())))
    assert len(rows) == 2400
print('Garmin decoder: valid FIT; pauses, two laps, distance, rider power and 2,400 ZIP originals verified')
