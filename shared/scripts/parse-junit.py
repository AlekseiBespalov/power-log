import json
import sys
import xml.etree.ElementTree as ET

cases = []
failed = False
for filename in sys.argv[1:]:
    root = ET.parse(filename).getroot()
    if root.tag not in ("testsuite", "testsuites"):
        raise ValueError(f"{filename}: expected JUnit testsuite(s)")
    failed |= any(element.tag in ("failure", "error", "skipped") for element in root.iter())
    suites = [root]
    while suites:
        suite = suites.pop()
        failed |= any(int(suite.get(key, "0")) > 0 for key in ("failures", "errors", "skipped", "disabled"))
        for element in suite:
            if element.tag in ("testsuite", "testsuites"):
                suites.append(element)
            elif element.tag == "testcase" and suite.tag == "testsuite":
                failed |= element.get("status", "").lower() in ("skipped", "ignored", "notrun", "disabled")
                cases.append({"name": element.attrib["name"], "classname": element.attrib["classname"]})
print(json.dumps({"cases": cases, "failed": failed}))
