import pathlib
import re
import sys

header = pathlib.Path(sys.argv[1]).read_text()
required = {
    'RouteRetentionCursor': ['initWithSelectedIndices'],
    'SecondBuckets': ['floorDivValue'],
    'SecondKernel': ['policyKind', 'aggregateBounds'],
    'HealthOriginSelector': ['latestIntervalsRecords', 'coverageIntervals', 'energyRecords', 'heartRateRecords'],
    'EnergyOriginPreparation': ['appendMetric'],
    'HeartRateOriginPreparation': ['appendRecord', 'scoresAndReturnError', 'selectedAndReturnError'],
    'HealthIntervalRevisionCursor': ['appendRecord', 'finishAndReturnError'],
    'Publication': ['planPlan', 'routeProjectionIntent'],
}
count = 0
for name, selectors in required.items():
    match = re.search(r'@interface PLC' + name + r'\s*:.*?\n@end', header, re.S)
    assert match is not None, name
    for selector in selectors:
        declarations = [line for line in match.group().splitlines() if re.search(r'\)' + selector + r'(?=[:; A-Z])', line)]
        assert len(declarations) == 1, (name, selector, declarations)
        assert 'NSError' in declarations[0], (name, selector, declarations[0])
        count += 1
codec = re.search(r'@interface PLCHealthCodec\s*:.*?\n@end', header, re.S)
assert codec is not None
for declaration in codec.group().splitlines():
    if declaration.startswith('- ('):
        assert 'NSError' in declaration, declaration
        count += 1
for name in ('MeasurementSource', 'Origin', 'EnergyInterval', 'EnergyPoint', 'HeartRateEvidence', 'HealthSelection'):
    match = re.search(r'@interface PLC' + name + r'\s*:.*?\n@end', header, re.S)
    assert match is not None, name
    constructors = [line for line in match.group().splitlines() if ')initWith' in line and
                    re.search(r'(source|producer|outcome):\(NSString', line, re.I)]
    assert len(constructors) == 1, (name, constructors)
    assert 'NSError' in constructors[0], (name, constructors[0])
    count += 1
assert 'PLCBatchPage' not in header
assert 'nextBatchPage:' not in header
print(f'{count} throwing Health Swift entry points carry NSError boundaries')
