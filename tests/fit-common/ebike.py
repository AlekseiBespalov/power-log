import pathlib
import struct

from garmin_fit_sdk import Decoder, Profile, Stream

APPLICATION_ID = bytes.fromhex("9b716e1808fe4c23b64fbe64d517cb48")
SINT16, FLOAT32 = 0x83, 0x88
DEVELOPER_FIELDS = {
    "battery_power": (0, SINT16, "W"),
    "battery_voltage": (1, FLOAT32, "V"),
    "battery_current": (2, FLOAT32, "A"),
    "motor_current": (3, FLOAT32, "A"),
    "motor_speed": (4, FLOAT32, "rpm"),
    "motor_temperature": (5, SINT16, "C"),
    "controller_temperature": (6, SINT16, "C"),
    "pedal_torque": (7, FLOAT32, "Nm"),
    "consumed_energy": (8, FLOAT32, "Wh"),
    "consumed_charge": (9, FLOAT32, "Ah"),
}


def read(path):
    definitions = []
    decoder = Decoder(Stream.from_file(str(path)))
    assert decoder.check_integrity(), f"header/file CRC integrity: {path}"
    messages, errors = Decoder(Stream.from_file(str(path))).read(mesg_definition_listener=definitions.append)
    assert not errors, errors
    check_wire(path)
    return messages, definitions


NATIVE_TYPES = {(20, 82): 0x84, (20, 119): 0x02, (18, 129): 0x84, (18, 130): 0x84}


def check_wire(path):
    for message in trace(path):
        for number, _, base_type in message["native"]:
            expected = NATIVE_TYPES.get((message["global"], number))
            assert expected is None or base_type == expected, (path, message["global"], number, hex(base_type))
        if message["global"] == 206:
            for text in (message["payload"][3:26], message["payload"][26:30]):
                end = text.index(0)
                assert text[:end].decode("ascii") and not any(text[end:]), (path, text)


def developer_names(messages):
    (identity,) = messages["developer_data_id_mesgs"]
    assert bytes(identity["application_id"]) == APPLICATION_ID, identity
    assert identity["developer_data_index"] == 0, identity
    assert set(identity) == {"application_id", "developer_data_index"}, identity
    names = {}
    for description in messages["field_description_mesgs"]:
        name = description["field_name"]
        number, base_type, units = DEVELOPER_FIELDS[name]
        assert description["developer_data_index"] == 0, description
        assert description["field_definition_number"] == number, description
        assert description["fit_base_type_id"] == base_type, description
        assert description["units"] == units, description
        assert not {"scale", "offset", "native_mesg_num", "native_field_num"} & set(description), description
        names[description["key"]] = name
    assert sorted(names.values()) == sorted(DEVELOPER_FIELDS), names
    native = {field["name"] for field in Profile["messages"][20]["fields"].values()}
    assert not native & set(names.values()), native & set(names.values())
    return names


def developer_values(record, names):
    return {names[key]: value for key, value in record.get("developer_fields", {}).items()}


def float32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def record_definitions(definitions):
    return [d for d in definitions if d["global_mesg_num"] == 20]


def schema(definition):
    return (
        definition["global_mesg_num"],
        tuple((f["field_id"], f["size"], f["base_type"]) for f in definition["field_definitions"]),
        tuple(
            (f["field_definition_number"], f["size"], f["developer_data_index"])
            for f in definition["developer_field_defs"]
        ),
    )


def trace(path):
    data = pathlib.Path(path).read_bytes()
    offset, end = data[0], data[0] + struct.unpack_from("<I", data, 4)[0]
    definitions, messages = {}, []
    while offset < end:
        header = data[offset]
        offset += 1
        assert header & 0x80 == 0, "compressed timestamp headers are not used"
        local = header & 0x0F
        if header & 0x40:
            assert data[offset + 1] == 0, "little-endian definitions"
            glob, count = struct.unpack_from("<HB", data, offset + 2)
            offset += 5
            native = [tuple(data[offset + 3 * i : offset + 3 * i + 3]) for i in range(count)]
            offset += 3 * count
            developer = []
            if header & 0x20:
                developer = [tuple(data[offset + 1 + 3 * i : offset + 4 + 3 * i]) for i in range(data[offset])]
                offset += 1 + 3 * len(developer)
            definitions[local] = (glob, native, developer)
        else:
            glob, native, developer = definitions[local]
            size = sum(f[1] for f in native) + sum(f[1] for f in developer)
            messages.append({"global": glob, "native": native, "developer": developer, "payload": data[offset : offset + size]})
            offset += size
    return messages
