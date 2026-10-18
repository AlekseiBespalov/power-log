import pathlib
import re
import sys

header = pathlib.Path(sys.argv[1]).read_text()
required = {
    'CanonicalNumber': ['encodeValue'],
    'WatchProtocol': ['decodeEventBytes', 'canonicalizeJsonBytes'],
    'Timestamps': ['parseOriginal'],
    'Sha256Accumulator': ['updateData', 'digestAndReturnError'],
    'ContentDigest': ['eventsEvents', 'secondsRecords'],
}
count = 0
for name, selectors in required.items():
    match = re.search(r'@interface PLC' + name + r'\s*:.*?\n@end', header, re.S)
    assert match is not None, name
    for selector in selectors:
        declarations = [line for line in match.group().splitlines() if re.search(r'\)' + selector + r'(?=[:; A-Z])', line)]
        assert len(declarations) == 1, (name, selector, declarations)
        for declaration in declarations:
            assert 'NSError' in declaration, (name, selector, declaration)
            count += 1
print(f'{count} throwing Swift entry points carry NSError boundaries')
