#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

"""Exercise release packaging with real APK signing and stubbed Gradle/GitHub."""

import base64
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]


class BuildIdentityTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.checkout = Path(directory.name)
        self.env = dict(
            os.environ,
            GIT_AUTHOR_NAME="Build fixture",
            GIT_AUTHOR_EMAIL="fixture@example.invalid",
            GIT_COMMITTER_NAME="Build fixture",
            GIT_COMMITTER_EMAIL="fixture@example.invalid",
            GIT_AUTHOR_DATE="2024-02-03T12:00:00+00:00",
            GIT_COMMITTER_DATE="2024-02-03T12:00:00+00:00",
        )
        self.project(self.checkout)

    def project(self, path):
        path.mkdir(parents=True, exist_ok=True)
        shutil.copy(REPO / "gradle/build-identity.gradle.kts", path / "identity.gradle.kts")
        (path / "settings.gradle.kts").write_text('rootProject.name = "identity-fixture"\n')
        (path / ".gitignore").write_text(".gradle/\nbuild/\n")
        (path / "build.gradle.kts").write_text(
            'apply(from = "identity.gradle.kts")\n'
            'tasks.register("identity") {\n'
            '    val debug = project.extra["developmentBuildIdentity"] as String\n'
            '    val release = project.extra["releaseBuildIdentity"] as String\n'
            '    val name = project.extra["releaseVersionName"] as String\n'
            '    val code = project.extra["releaseVersionCode"] as Int\n'
            '    doLast { println("DEBUG=$debug"); println("RELEASE=$release"); println("VERSION=$name/$code") }\n'
            "}\n"
        )

    def git(self, *args):
        return subprocess.check_output(
            ["git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args],
            cwd=self.checkout,
            env=self.env,
            text=True,
            stderr=subprocess.PIPE,
        ).strip()

    def identity(self, path=None):
        return subprocess.check_output(
            [
                str(REPO / "gradlew"),
                "-p",
                str(path or self.checkout),
                "identity",
                "--configuration-cache",
                "--console=plain",
                "--max-workers=1",
                "-Dorg.gradle.jvmargs=-Xmx1280m -Dfile.encoding=UTF-8",
            ],
            env=self.env,
            text=True,
            stderr=subprocess.STDOUT,
        )

    def test_tag_dirty_worktree_and_new_commit_refresh_cached_identity(self):
        self.git("init")
        self.git("add", ".")
        self.git("commit", "-m", "Fixture")
        revision = f"{self.git('rev-parse', '--short=12', 'HEAD')} (2024-02-03)"
        self.assertIn(f"RELEASE={revision}", self.identity())
        self.assertIn("Reusing configuration cache", self.identity())
        self.git("tag", "v1.0.0")
        tagged = self.identity()
        self.assertIn(f"RELEASE=1.0.0 · {revision}", tagged)
        self.assertIn(f"DEBUG={revision}", tagged)
        self.assertIn("VERSION=1.0.0/10000", tagged)
        (self.checkout / "new-source.txt").write_text("Uncommitted source\n")
        dirty = self.identity()
        self.assertIn(" · modified (2024-02-03)", dirty)
        self.assertNotIn("RELEASE=1.0.0", dirty)
        self.assertIn("VERSION=1.0.0-dev/10000", dirty)
        self.git("add", ".")
        self.env["GIT_COMMITTER_DATE"] = "2024-02-04T12:00:00+00:00"
        self.git("commit", "-m", "Next fixture")
        updated = f"{self.git('rev-parse', '--short=12', 'HEAD')} (2024-02-04)"
        self.assertIn(f"RELEASE={updated}", self.identity())
        self.git("tag", "v1.2.3")
        self.assertIn("VERSION=1.2.3/10203", self.identity())

    def test_broken_checkout_reports_git_error_instead_of_building_unknown_revision(self):
        (self.checkout / ".git").mkdir()
        with self.assertRaises(subprocess.CalledProcessError) as failure:
            self.identity()
        self.assertIn("Cannot determine the build's Git revision", failure.exception.output)
        self.assertIn("not a git repository", failure.exception.output)
        self.assertNotIn("DEBUG=Development build", failure.exception.output)

    def test_linked_worktree_and_signature_display_config_preserve_identity(self):
        self.git("init")
        self.git("add", ".")
        self.git("commit", "-m", "Fixture")
        self.git("config", "log.showSignature", "true")
        with tempfile.TemporaryDirectory() as directory:
            worktree = Path(directory) / "linked"
            self.git("worktree", "add", "--detach", str(worktree))
            try:
                revision = f"{self.git('rev-parse', '--short=12', 'HEAD')} (2024-02-03)"
                self.assertTrue((worktree / ".git").is_file())
                self.assertIn(f"DEBUG={revision}", self.identity(worktree))
                self.assertIn("Reusing configuration cache", self.identity(worktree))
            finally:
                self.git("worktree", "remove", "--force", str(worktree))

    def test_missing_metadata_does_not_use_an_enclosing_repository(self):
        fallback = "RELEASE=Development build · Git revision unavailable"
        self.assertIn(fallback, self.identity())
        self.git("init")
        self.git("add", ".")
        self.git("commit", "-m", "Fixture")
        self.assertNotIn(fallback, self.identity())
        nested = self.checkout / "source-archive"
        self.project(nested)
        self.assertIn(fallback, self.identity(nested))


class ReleaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.fixtures.cleanup)
        fixture = Path(cls.fixtures.name)
        sdk = Path(os.environ["ANDROID_HOME"])
        cls.env = dict(os.environ, CI="true")
        cls.env.update(
            ANDROID_KEYSTORE_PASSWORD=secrets.token_urlsafe(24), ANDROID_KEY_ALIAS="test-only"
        )
        cls.env["ANDROID_KEY_PASSWORD"] = cls.env["ANDROID_KEYSTORE_PASSWORD"]
        key = fixture / "test.p12"
        subprocess.run(
            [
                "keytool",
                "-genkeypair",
                "-keystore",
                str(key),
                "-storetype",
                "PKCS12",
                "-storepass:env",
                "ANDROID_KEYSTORE_PASSWORD",
                "-keypass:env",
                "ANDROID_KEY_PASSWORD",
                "-alias",
                "test-only",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "1",
                "-dname",
                "CN=Disposable release test",
                "-noprompt",
            ],
            env=cls.env,
            check=True,
            capture_output=True,
        )
        certificate = subprocess.check_output(
            [
                "keytool",
                "-exportcert",
                "-keystore",
                str(key),
                "-storepass:env",
                "ANDROID_KEYSTORE_PASSWORD",
                "-alias",
                "test-only",
            ],
            env=cls.env,
        )
        cls.certificate_pin = hashlib.sha256(certificate).hexdigest()
        cls.env["ANDROID_KEYSTORE_BASE64"] = base64.b64encode(key.read_bytes()).decode()
        manifest = fixture / "AndroidManifest.xml"
        manifest.write_text("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="dev.finnes.releasefixture" android:versionCode="1" android:versionName="1.0">
            <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/>
            <application android:hasCode="false"/>
            </manifest>""")
        cls.apk = fixture / "fixture.apk"
        subprocess.run(
            [
                str(sdk / "build-tools/36.0.0/aapt2"),
                "link",
                "--manifest",
                str(manifest),
                "-I",
                str(sdk / "platforms/android-37.0/android.jar"),
                "-o",
                str(cls.apk),
            ],
            check=True,
            capture_output=True,
        )

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for directory in ["scripts", "bin", "tmp", "build/release"]:
            (self.root / directory).mkdir(parents=True)
        for name in ["release.sh", "sign-release.sh", "publish-release.sh", "check-apk-pages.py"]:
            shutil.copyfile(REPO / "scripts" / name, self.root / "scripts" / name)
        (self.root / "signing-certificate.sha256").write_text(self.certificate_pin + "\n")
        self.env = dict(
            self.env,
            TMPDIR=str(self.root / "tmp"),
            GITHUB_REPOSITORY="example/portemone",
            PATH=str(self.root / "bin") + os.pathsep + self.env["PATH"],
        )
        for module in ["app", "notification-companion"]:
            apk_dir = self.root / module / "build/outputs/apk/release"
            apk_dir.mkdir(parents=True)
            shutil.copyfile(self.apk, apk_dir / f"{module}-release-unsigned.apk")
            report = self.root / module / "build/reports/cyclonedx-direct"
            report.mkdir(parents=True)
            (report / "bom.json").write_text(json.dumps({"fixture": module}))
        self.executable(
            "gradlew",
            """import os, sys
assert not any(name in os.environ for name in [
    'ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD',
    'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD'])
assert {':app:assembleRelease', ':app:lintRelease',
        ':notification-companion:assembleRelease',
        ':notification-companion:lintRelease', 'cyclonedxBom'} <= set(sys.argv)
sys.exit(int(os.environ.get('BUILD_FAIL', '0')))
""",
        )
        self.executable(
            "bin/gh",
            """import json, os, sys
from pathlib import Path
with Path('calls.jsonl').open('a') as output:
    output.write(json.dumps(sys.argv[1:]) + '\\n')
if sys.argv[1] == 'api':
    status = os.environ.get('RELEASE_STATUS', 'missing')
    print(json.dumps([[] if status == 'missing' else [
        {'tag_name': 'v1.0.0', 'draft': status == 'draft'}]]))
elif sys.argv[1:3] == ['release', 'upload'] and os.environ.get('UPLOAD_FAIL'):
    sys.exit(1)
""",
        )

    def executable(self, path, body):
        file = self.root / path
        file.write_text("#!/usr/bin/python3\n" + body)
        file.chmod(0o700)

    def run_script(self, name, *args, **env):
        return subprocess.run(
            ["bash", str(self.root / "scripts" / name), *args],
            env=dict(self.env, **env),
            capture_output=True,
            text=True,
        )

    def test_signed_release_replaces_stale_output(self):
        output = self.root / "build/release"
        (output / "obsolete.apk").write_text("old release")
        result = self.run_script("release.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            {p.name for p in output.iterdir()},
            {
                "portemone.apk",
                "portemone-notification-companion.apk",
                "portemone.cdx.json",
                "portemone-notification-companion.cdx.json",
                "SHA256SUMS",
            },
        )
        certificates = [
            line
            for line in result.stdout.splitlines()
            if line.startswith("Signer #1 certificate SHA-256 digest:")
        ]
        self.assertEqual(len(certificates), 2)
        self.assertEqual(certificates[0], certificates[1])
        subprocess.run(
            ["sha256sum", "--check", "SHA256SUMS"], cwd=output, check=True, capture_output=True
        )
        self.assertEqual(list((self.root / "tmp").iterdir()), [])

    def test_failure_preserves_previous_release_and_cleans_key(self):
        sentinel = self.root / "build/release/previous.apk"
        sentinel.write_text("previous release")
        for override in [
            {"BUILD_FAIL": "1"},
            {"ANDROID_KEY_ALIAS": "missing"},
            {"ANDROID_KEYSTORE_BASE64": "invalid base64!"},
        ]:
            with self.subTest(override=list(override)):
                self.assertNotEqual(self.run_script("release.sh", **override).returncode, 0)
                self.assertEqual(sentinel.read_text(), "previous release")
                self.assertEqual(list((self.root / "tmp").iterdir()), [])
                self.assertEqual(list((self.root / "build").glob("release.*")), [])

    def test_missing_or_wrong_certificate_blocks_packaging(self):
        sentinel = self.root / "build/release/previous.apk"
        sentinel.write_text("previous release")
        for pin in ["", "0" * 64]:
            with self.subTest(pin=pin):
                (self.root / "signing-certificate.sha256").write_text(pin)
                result = self.run_script("release.sh")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("certificate", result.stderr)
                self.assertEqual(sentinel.read_text(), "previous release")
                self.assertEqual(list((self.root / "tmp").iterdir()), [])
                self.assertEqual(list((self.root / "build").glob("release.*")), [])

    def test_publish_only_after_upload_and_retry_drafts(self):
        for status, fail, expected in [
            ("missing", "", ["create", "upload", "edit"]),
            ("draft", "", ["upload", "edit"]),
            ("missing", "1", ["create", "upload"]),
            ("published", "", []),
        ]:
            with self.subTest(status=status, fail=fail):
                log = self.root / "build/release/calls.jsonl"
                log.unlink(missing_ok=True)
                result = self.run_script(
                    "publish-release.sh", "v1.0.0", RELEASE_STATUS=status, UPLOAD_FAIL=fail
                )
                calls = [json.loads(line) for line in log.read_text().splitlines()]
                release_calls = [call for call in calls if call[0] == "release"]
                self.assertEqual([call[1] for call in release_calls], expected)
                self.assertEqual(result.returncode == 0, not fail and status != "published")
                for call in release_calls:
                    if call[1] == "create":
                        self.assertIn("--draft", call)
                    if call[1] == "upload":
                        self.assertIn("portemone.apk", call)
                        self.assertIn("portemone-notification-companion.apk", call)
                    if call[1] == "edit":
                        self.assertIn("--draft=false", call)


class VersionTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null")
        self.git("init", "-q")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "test@example.invalid")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, env=self.env, text=True)

    def tag_version(self, name):
        (self.root / "source").write_text(name)
        self.git("add", "source")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "Fixture")
        self.git("-c", "tag.gpgsign=false", "tag", "-a", f"v{name}", "-m", "Fixture")

    def check(self, tag):
        return subprocess.run(
            [sys.executable, str(REPO / "scripts/check-release-version.py"), tag],
            cwd=self.root,
            env=self.env,
            capture_output=True,
            text=True,
        )

    def test_first_release_and_increment_allow_retries(self):
        self.tag_version("1.0.0")
        self.assertIn("versionCode 10000", self.check("v1.0.0").stdout)
        self.tag_version("1.2.3")
        for _ in range(2):
            result = self.check("v1.2.3")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("versionCode 10203", result.stdout)

    def test_older_release_is_rejected_even_on_newer_commit(self):
        self.tag_version("2.0.0")
        self.tag_version("1.99.99")
        result = self.check("v1.99.99")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must exceed 20000 from v2.0.0", result.stderr)

    def test_invalid_and_overflowing_versions_are_rejected(self):
        for name in ["0.0.0", "1.100.0", "1.0.100", "210000.0.1", "01.0.0", "1.0.0-rc.1"]:
            with self.subTest(name=name):
                self.tag_version(name)
                self.assertNotEqual(self.check(f"v{name}").returncode, 0)

    def test_tag_must_match_commit_and_be_unambiguous(self):
        self.tag_version("1.0.0")
        self.git("-c", "tag.gpgsign=false", "tag", "v1.0.1")
        self.assertIn("exactly one version tag", self.check("v1.0.0").stderr)
        (self.root / "extra").touch()
        self.git("add", "extra")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "Later commit")
        result = self.check("v1.0.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checked-out commit", result.stderr)


if __name__ == "__main__":
    unittest.main()
