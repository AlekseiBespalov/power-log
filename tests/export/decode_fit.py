"""Decodes a FIT file with the official Garmin FIT SDK and prints JSON for the core export tests."""
import datetime
import json
import math
from pathlib import Path
import sys

from garmin_fit_sdk import Decoder, Stream

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "fit-common"))
from ebike import developer_names, developer_values, read, schema, trace  # noqa: E402

FIT_EPOCH = datetime.datetime(1989, 12, 31, tzinfo=datetime.timezone.utc)


def plain(value):
    if isinstance(value, datetime.datetime):
        return round((value - FIT_EPOCH).total_seconds())
    if isinstance(value, dict):
        return {str(key): plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [plain(item) for item in value]
    if isinstance(value, (bytes, bytearray)):
        return list(value)
    if isinstance(value, float) and not math.isfinite(value):
        return repr(value)
    return value


def decode(path):
    data = Path(path).read_bytes()
    messages, definitions = read(path)
    names = developer_names(messages)
    ordered = []
    Decoder(Stream.from_file(str(path))).read(mesg_listener=lambda number, message: ordered.append((number, message)))
    listing = []
    for number, message in ordered:
        fields = dict(message)
        if "developer_fields" in fields:
            fields["developer_fields"] = developer_values(message, names)
        listing.append({"mesg_num": number, "fields": plain(fields)})
    return {
        "header": {
            "size": data[0],
            "protocol": data[1],
            "profile": int.from_bytes(data[2:4], "little"),
            "data_size": int.from_bytes(data[4:8], "little"),
            "type": data[8:12].decode("ascii"),
        },
        "messages": listing,
        "definitions": [
            {"local": definition["local_mesg_num"], "schema": plain(schema(definition))} for definition in definitions
        ],
        "trace": [
            {
                "global": message["global"],
                "native": [list(field) for field in message["native"]],
                "developer": [list(field) for field in message["developer"]],
                "payload": message["payload"].hex(),
            }
            for message in trace(path)
        ],
    }


if __name__ == "__main__":
    json.dump(decode(sys.argv[1]), sys.stdout)
