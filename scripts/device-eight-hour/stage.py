import hashlib
import shutil
import sqlite3
import sys
from pathlib import Path


def prepare(source, destination):
    destination.mkdir()
    database = source / "power-log.sqlite3"
    with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as original:
        with sqlite3.connect(destination / database.name) as copy:
            original.backup(copy)
            for table in [
                "derived_cache",
                "distance_points",
                "distance_snapshots",
                "distance_generations",
            ]:
                copy.execute("DELETE FROM " + table)
    archive = source / "workouts"
    if archive.exists():
        shutil.copytree(archive, destination / "workouts")
        for cache in (destination / "workouts").rglob("summary-*"):
            if cache.is_file():
                cache.unlink()


source = Path('test-results/device-eight-hour/original/PowerLog').resolve()
if len(sys.argv) > 1:
    staging = Path(sys.argv[1]).resolve()
else:
    staging = Path('test-results/device-eight-hour/cold/PowerLog').resolve()
    if staging.exists():
        shutil.rmtree(staging)
    staging.parent.mkdir(parents=True, exist_ok=True)
    prepare(source, staging)
derived = {'derived_cache', 'distance_points', 'distance_snapshots', 'distance_generations'}
with sqlite3.connect(source / 'power-log.sqlite3') as a, sqlite3.connect(staging / 'power-log.sqlite3') as b:
    tables = [row[0] for row in a.execute("SELECT name FROM sqlite_master WHERE type='table'")]
    for table in tables:
        if table in derived:
            assert b.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0] == 0
            continue
        left = sorted(repr(row) for row in a.execute(f'SELECT * FROM "{table}"'))
        right = sorted(repr(row) for row in b.execute(f'SELECT * FROM "{table}"'))
        assert left == right, table
    ride = a.execute("SELECT id FROM collections WHERE kind='workout' ORDER BY event_count DESC LIMIT 1").fetchone()[0]
    counts = a.execute('SELECT kind,count(*) FROM collection_memberships WHERE collection_id=? GROUP BY kind', (ride,)).fetchall()
    assert dict(counts)['telemetry'] == 230400
    assert dict(counts)['location'] == 28800
    assert dict(counts)['health'] == 28801
    print('rideID', ride, 'counts', counts)
    print('originalSHA256', hashlib.sha256((source / 'power-log.sqlite3').read_bytes()).hexdigest())
