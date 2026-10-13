#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

"""Require a matching release tag and an increasing Android version code."""

import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def version(tag):
    match = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
    if not match:
        raise ValueError("Expected a stable tag such as v1.0.0")
    major, minor, patch = map(int, match.groups())
    code = major * 10000 + minor * 100 + patch
    if major > 210000 or minor > 99 or patch > 99 or not 1 <= code <= 2_100_000_000:
        raise ValueError("Version exceeds Android code or major/minor/patch bounds")
    return code


def check(tag):
    if git("rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("Release checks require a full checkout with all version tags")
    code = version(tag)
    if git("rev-parse", f"refs/tags/{tag}^{{commit}}") != git("rev-parse", "HEAD"):
        raise ValueError("The release tag must point to the checked-out commit")
    exact = [
        name for name in git("tag", "--points-at", "HEAD").splitlines() if name.startswith("v")
    ]
    if exact != [tag]:
        raise ValueError("The release commit must have exactly one version tag")
    for previous in git("tag", "--list", "v*").splitlines():
        if previous == tag or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", previous):
            continue
        previous_code = version(previous)
        if code <= previous_code:
            raise ValueError(f"versionCode {code} must exceed {previous_code} from {previous}")
    print(f"{tag}: versionCode {code} exceeds all other version tags")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("Usage: check-release-version.py v1.0.0")
        check(sys.argv[1])
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
