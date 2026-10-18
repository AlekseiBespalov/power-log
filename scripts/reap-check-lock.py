import fcntl
import json
import os
import sys
import uuid


def reap(path):
    try:
        owner = open(path, 'r', encoding='utf-8')
    except FileNotFoundError:
        return True
    except OSError:
        return False
    with owner:
        try:
            fcntl.flock(owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            return False
        try:
            current = os.stat(path, follow_symlinks=False)
        except FileNotFoundError:
            return True
        except OSError:
            return False
        opened = os.fstat(owner.fileno())
        # Another reaper may have replaced the pathname while this descriptor was opening.
        if (current.st_dev, current.st_ino) != (opened.st_dev, opened.st_ino):
            return True
        try:
            record = json.load(owner)
            pid = record.get('pid') if isinstance(record, dict) else None
        except (OSError, ValueError):
            return False
        if type(pid) is not int or not 0 < pid <= 2147483647:
            return False
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            retired = f'{path}.stale-{uuid.uuid4()}'
            os.rename(path, retired)
            os.unlink(retired)
            return True
        except OSError:
            return False
        return False


if __name__ == '__main__':
    sys.exit(0 if reap(sys.argv[1]) else 1)
