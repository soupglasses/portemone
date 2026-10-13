#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

"""Do not let a green instrumentation job hide assumptions that skipped regressions."""

import pathlib
import sys
import xml.etree.ElementTree as ET

reports = list(pathlib.Path(sys.argv[1]).rglob("TEST-*.xml"))
if not reports:
    raise SystemExit("No instrumentation reports found")
cases = [case for report in reports for case in ET.parse(report).iter("testcase")]
failed = [
    case
    for case in cases
    if any(case.find(tag) is not None for tag in ("skipped", "failure", "error"))
]
if not cases or failed:
    for case in failed:
        print(f"Not passed: {case.get('classname')}.{case.get('name')}")
    raise SystemExit("Every discovered regression test must run and pass")
print(f"All {len(cases)} discovered regression tests passed")
