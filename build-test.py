"""Build a separately signed, dependency-free Android Instrumentation APK.

Run build.py first. This module only builds the runner; it never installs or starts it.
The main app owns Vosk/JNA classes, native libraries and the bundled recognition model.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import zipfile

import build as app

ROOT = app.ROOT
WORK = ROOT / "work" / "test-build"
OUT = app.ROOT / "work" / "verification"


def relative(path):
    """aapt2 on Windows cannot open an absolute manifest/asset path containing Chinese."""
    return str(path.relative_to(ROOT))


def clean_local_directory(path):
    resolved = path.resolve()
    if resolved.parent != WORK.resolve():
        raise RuntimeError(f"Refusing to clean outside the test build directory: {resolved}")
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)


def main():
    main_classes = app.WORK / "app-classes.jar"
    libraries = [ROOT / "vendor" / name / "classes.jar" for name in ("vosk", "jna", "tesseract")]
    signing = ROOT / "signing" / "langyu-release.p12"
    for required in [main_classes, signing, app.ANDROID] + libraries:
        if not required.is_file():
            raise SystemExit(f"Run build.py first; required build input missing: {required}")
    WORK.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(exist_ok=True)
    for name in ("generated", "classes", "dex", "assets"):
        clean_local_directory(WORK / name)
    sample = ROOT / "tests" / "assets" / "chinese.wav"
    if sample.is_file():
        shutil.copyfile(sample, WORK / "assets" / "chinese.wav")
    app.run(app.BT / "aapt2.exe", "link", "-I", app.ANDROID,
            "--manifest", relative(ROOT / "tests" / "android" / "AndroidManifest.xml"),
            "--java", relative(WORK / "generated"), "--min-sdk-version", "29",
            "--target-sdk-version", "35", "-A", relative(WORK / "assets"),
            "-o", relative(WORK / "resources.apk"))
    classpath = os.pathsep.join(str(path) for path in [app.ANDROID, main_classes] + libraries)
    sources = sorted((ROOT / "tests" / "android").rglob("*.java"))
    arguments = ["-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
                 "-classpath", classpath, "-d", str(WORK / "classes")] + [str(path) for path in sources]
    argfile = WORK / "javac.args"
    argfile.write_text("\n".join('"' + text.replace("\\", "/") + '"' for text in arguments), encoding="utf-8")
    app.run(app.JDK / "bin" / "javac.exe", "-J-Dfile.encoding=UTF-8", "@" + relative(argfile).replace("\\", "/"))
    test_classes = WORK / "test-classes.jar"
    allowed_prefixes = tuple("com/langyu/assistant/" + source.stem for source in sources)
    with zipfile.ZipFile(test_classes, "w", zipfile.ZIP_DEFLATED) as archive:
        for compiled in sorted((WORK / "classes").rglob("*.class")):
            entry = compiled.relative_to(WORK / "classes").as_posix()
            if not entry.startswith(allowed_prefixes):
                raise RuntimeError(f"Unexpected application/dependency class in test output: {entry}")
            archive.write(compiled, entry)
    app.run(app.JDK / "bin" / "java.exe", "-cp", app.BT / "lib" / "d8.jar",
            "com.android.tools.r8.D8", "--release", "--min-api", "29", "--lib", app.ANDROID,
            "--classpath", main_classes, "--classpath", libraries[0], "--classpath", libraries[1], "--classpath", libraries[2],
            "--output", WORK / "dex", test_classes)
    unsigned = WORK / "unsigned.apk"
    shutil.copyfile(WORK / "resources.apk", unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        for dex in sorted((WORK / "dex").glob("*.dex")):
            archive.write(dex, dex.name)
    app.run(app.BT / "zipalign.exe", "-f", "-p", "4", unsigned, WORK / "aligned.apk")
    apk = OUT / "Langyu-instrumentation.apk"
    app.run(app.JDK / "bin" / "java.exe", "-jar", app.BT / "lib" / "apksigner.jar", "sign",
            "--ks", signing, "--ks-key-alias", "langyu", "--ks-pass", "pass:langyu-local-release",
            "--key-pass", "pass:langyu-local-release", "--out", apk, WORK / "aligned.apk")
    app.run(app.JDK / "bin" / "java.exe", "-jar", app.BT / "lib" / "apksigner.jar", "verify",
            "--verbose", "--print-certs", apk)
    app.run(app.BT / "zipalign.exe", "-c", "4", apk)
    with zipfile.ZipFile(apk) as archive:
        if any(entry.startswith("lib/") for entry in archive.namelist()):
            raise RuntimeError("Test APK unexpectedly contains app native libraries")
    print(json.dumps({"apk": str(apk), "bytes": apk.stat().st_size,
                      "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                      "chinese_sample": sample.is_file(),
                      "runner": "com.langyu.assistant.tests/com.langyu.assistant.SmokeInstrumentation"},
                     ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
