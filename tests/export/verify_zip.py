"""Independently extracts a Power Log ride-data ZIP and checks its container rules.

Usage: python3 tests/export/verify_zip.py <file.zip>

Prints one JSON object describing every entry, including its extracted text, and exits non-zero when a rule fails.
"""

import csv
import io
import json
import struct
import sys
import zipfile
import zlib

FOLDER = "PowerLog-original/"
ENTRIES = ["telemetry.csv", "gps.csv", "health.csv", "events.csv", "datapackage.json"]
LIMIT = 0xFFFFFFFF
UTF8_NAMES = 0x0800
DEFLATED = 8
VERSION = 45


class Failure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)


def end_records(data):
    end = len(data) - 22
    check(end >= 0 and data[end : end + 4] == b"PK\x05\x06", "the end of central directory record is not last")
    disk, start_disk, on_disk, total, size, offset, comment = struct.unpack("<HHHHIIH", data[end + 4 : end + 22])
    check(comment == 0 and disk == start_disk == 0 and on_disk == total, "the end record is not a one-disk record")
    zip64 = data[end - 20 : end - 16] == b"PK\x06\x07"
    record = {"entries": total, "size": size, "offset": offset, "zip64": zip64}
    if zip64:
        locator_disk, record_offset, disks = struct.unpack("<IQI", data[end - 16 : end])
        check(locator_disk == 0 and disks == 1, "the ZIP64 locator is not a one-disk locator")
        signature = data[record_offset : record_offset + 4]
        check(signature == b"PK\x06\x06", "the ZIP64 locator does not point to its record")
        fields = struct.unpack("<QHHIIQQQQ", data[record_offset + 4 : record_offset + 56])
        remaining, made, needed, disk64, start64, on_disk64, total64, size64, offset64 = fields
        check(remaining == 44 and made == VERSION and needed == VERSION, "the ZIP64 end record header is wrong")
        check(disk64 == 0 and start64 == 0 and on_disk64 == total64 == total, "the ZIP64 end record counts are wrong")
        check(record_offset == offset64 + size64, "the ZIP64 end record does not follow the central directory")
        check(record_offset + 56 == end - 20, "the ZIP64 locator does not follow its record")
        check(offset == LIMIT and size == size64, "the end record does not defer its offset to ZIP64")
        record.update(size=size64, offset=offset64)
    else:
        check(offset < LIMIT, "a central directory offset at or above 4 GiB needs ZIP64 end records")
    return record


def zip64_extra(extra):
    check(len(extra) >= 4, "the extra field is truncated")
    tag, size = struct.unpack("<HH", extra[:4])
    check(tag == 0x0001 and size == len(extra) - 4, "the extra field is not exactly one ZIP64 block")
    return extra[4:]


def verify(path):
    with open(path, "rb") as handle:
        data = handle.read()
    end = end_records(data)
    entries = []
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        check(names == [FOLDER + name for name in ENTRIES], "the entries are not the five ride files in order")
        check(end["entries"] == len(infos), "the end record counts the wrong number of entries")
        check(archive.testzip() is None, "an entry fails its CRC-32")
        expected_offset = 0
        for info in infos:
            name = info.filename.encode("utf-8")
            check(not info.is_dir(), "the archive has a directory entry")
            check(info.flag_bits == UTF8_NAMES, f"{info.filename}: general purpose flags are {info.flag_bits:#06x}")
            check(info.compress_type == DEFLATED, f"{info.filename}: the entry is not DEFLATE")
            check(info.extract_version == VERSION, f"{info.filename}: version needed is {info.extract_version}")
            check(info.create_version == VERSION and info.create_system == 0, f"{info.filename}: version made by")
            check(info.header_offset == expected_offset, f"{info.filename}: the entry does not follow the previous one")
            large = [value for value in (info.file_size, info.compress_size, info.header_offset) if value >= LIMIT]
            if large:
                expected = struct.pack("<" + "Q" * len(large), *large)
                check(zip64_extra(info.extra) == expected, f"{info.filename}: central ZIP64 extra")
            else:
                check(info.extra == b"", f"{info.filename}: the central record has an unneeded extra field")
            at = info.header_offset
            check(data[at : at + 4] == b"PK\x03\x04", f"{info.filename}: no local header")
            fields = struct.unpack("<HHHHHIIIHH", data[at + 4 : at + 30])
            needed, flags, method, time, date, crc, compressed, size, name_length, extra_length = fields
            check((needed, flags, method) == (VERSION, UTF8_NAMES, DEFLATED), f"{info.filename}: local header fields")
            check(crc == info.CRC, f"{info.filename}: the local CRC-32 was not patched")
            check(compressed == LIMIT and size == LIMIT, f"{info.filename}: the local header is not in ZIP64 form")
            check(data[at + 30 : at + 30 + name_length] == name, f"{info.filename}: the local name differs")
            check(extra_length == 20, f"{info.filename}: the local extra field is not one ZIP64 block")
            local_extra = data[at + 30 + name_length : at + 30 + name_length + extra_length]
            sizes = struct.unpack("<QQ", zip64_extra(local_extra))
            check(sizes == (info.file_size, info.compress_size), f"{info.filename}: local ZIP64 sizes not patched")
            year, month, day, hour, minute, second = info.date_time
            central = ((hour << 11) | (minute << 5) | (second // 2), ((year - 1980) << 9) | (month << 5) | day)
            check((time, date) == central, f"{info.filename}: local and central times differ")
            expected_offset = at + 30 + name_length + extra_length + info.compress_size
            raw = archive.read(info)
            check(len(raw) == info.file_size and len(raw) > 0, f"{info.filename}: the extracted size is wrong")
            check(not raw.startswith(b"\xef\xbb\xbf"), f"{info.filename}: the text starts with a byte order mark")
            check(raw.endswith(b"\n"), f"{info.filename}: the text does not end with LF")
            text = raw.decode("utf-8")
            entries.append(
                {
                    "name": info.filename,
                    "flags": info.flag_bits,
                    "method": info.compress_type,
                    "versionNeeded": info.extract_version,
                    "versionMadeBy": info.create_version,
                    "dosTime": time,
                    "dosDate": date,
                    "dateTime": list(info.date_time),
                    "crc": info.CRC,
                    "size": info.file_size,
                    "compressedSize": info.compress_size,
                    "offset": info.header_offset,
                    "text": text,
                }
            )
        check(end["offset"] == expected_offset, "the central directory does not follow the last entry")
    descriptor = json.loads(entries[-1]["text"])
    resources = {resource["path"]: resource for resource in descriptor["resources"]}
    check([resource["path"] for resource in descriptor["resources"]] == ENTRIES[:4], "the descriptor resources")
    for entry in entries[:4]:
        resource = resources[entry["name"][len(FOLDER) :]]
        rows = list(csv.reader(io.StringIO(entry["text"], newline=""), strict=True))
        names = [field["name"] for field in resource["schema"]["fields"]]
        check(rows and rows[0] == names, f"{entry['name']}: the header differs from the descriptor schema")
        check(all(len(row) == len(names) for row in rows), f"{entry['name']}: a record has the wrong number of cells")
        entry["records"] = len(rows) - 1
    return {"entries": entries, "zip64End": end["zip64"]}


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    try:
        result = verify(sys.argv[1])
    except (Failure, csv.Error, zipfile.BadZipFile, zlib.error, EOFError, ValueError, LookupError) as error:
        print(f"verify_zip: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
