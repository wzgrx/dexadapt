#!/usr/bin/env python3
"""在独立缓存中按固定源码构建 DexKit 与 FlatBuffers。"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK_FILE = ROOT / "gradle/dexkit-source.json"
LOCK = json.loads(LOCK_FILE.read_text())
VERSION = LOCK["dexkitVersion"]
BASE = Path(os.environ.get("DEXKIT_SOURCE_HOME", Path.home() / "tools/dexkit-source")).resolve()
WORK = Path(os.environ.get("DEXKIT_BUILD_DIR", BASE / "build" / VERSION)).resolve()
REPOSITORY = Path(os.environ.get("DEXKIT_MAVEN_REPO", BASE / "maven")).resolve()
DESKTOP = Path(os.environ.get("DEXKIT_DESKTOP_HOME", Path.home() / "tools/dexkit-desktop")).resolve() / VERSION
RECEIPT = BASE / (VERSION + ".json")
RECIPE = hashlib.sha256(Path(__file__).read_bytes() + LOCK_FILE.read_bytes()).hexdigest()


def run(arguments, cwd=None, env=None):
    subprocess.run([str(arg) for arg in arguments], cwd=cwd, env=env, check=True)


def digest(file):
    with Path(file).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def cached():
    try:
        receipt = json.loads(RECEIPT.read_text())
        return (
            receipt.get("recipe") == RECIPE
            and receipt.get("lock") == LOCK
            and receipt.get("repository") == str(REPOSITORY)
            and len(receipt["artifacts"]) == 5
            and all(Path(name).is_file() and digest(name) == sha
                    for name, sha in receipt["artifacts"].items())
        )
    except (OSError, ValueError, KeyError, TypeError):
        return False


def checkout(name, url, commit):
    destination = WORK / name
    if not destination.exists():
        run(["git", "clone", "--no-checkout", "--depth", "1", url, destination])
        run(["git", "-C", destination, "fetch", "--depth", "1", "origin", commit])
        run(["git", "-C", destination, "checkout", "--detach", "FETCH_HEAD"])
    actual = subprocess.check_output(["git", "-C", destination, "rev-parse", "HEAD"], text=True).strip()
    if actual != commit:
        raise RuntimeError("Pinned dependency commit mismatch in " + str(destination))
    directory = destination.resolve()
    top = Path(subprocess.check_output(
        ["git", "-C", destination, "rev-parse", "--show-toplevel"], text=True).strip()).resolve()
    if destination.is_symlink() or not directory.is_relative_to(WORK) or top != directory:
        raise RuntimeError("Source checkout is outside the private build cache")
    extra = subprocess.check_output(
        ["git", "-C", destination, "ls-files", "--others", "--exclude-standard"], text=True).strip()
    generated_prefix = "Core/third_party/flatbuffers/include/" if name == "DexKit" else ""
    if any(not generated_prefix or not entry.startswith(generated_prefix)
           for entry in extra.splitlines()):
        raise RuntimeError("Unexpected source files in the private dependency checkout")
    # Restore only tracked files in this controlled checkout before applying the recipe.
    run(["git", "-C", destination, "reset", "--hard", commit])
    return destination


def patch(file, old, new):
    text = file.read_text()
    if old in text:
        file.write_text(text.replace(old, new))
    elif new not in text:
        raise RuntimeError("Upstream build layout changed at " + str(file))


def tests(folder):
    reports = [ET.parse(path).getroot() for path in folder.glob("TEST-*.xml")]
    totals = {key: sum(int(report.get(key, "0")) for report in reports)
              for key in ("tests", "failures", "errors", "skipped")}
    if not totals["tests"] or totals["failures"] or totals["errors"]:
        raise RuntimeError("Dependency tests failed or did not run in " + str(folder))
    return totals


def publish_file(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    shutil.copyfile(source, temporary)
    temporary.replace(destination)


def build():
    for program in ("git", "cmake", "ninja", "clang", "clang++", "mvn"):
        if shutil.which(program) is None:
            raise RuntimeError("Required build tool is missing: " + program)
    java_home = Path(os.environ.get("DEXKIT_BUILD_JAVA_HOME", Path.home() / "tools/temurin17-dexkit"))
    if not (java_home / "bin/java").is_file():
        raise RuntimeError("Set DEXKIT_BUILD_JAVA_HOME to a JDK 17 installation for the upstream build")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    if not (sdk / "ndk" / LOCK["ndkVersion"]).is_dir():
        raise RuntimeError("Install Android NDK " + LOCK["ndkVersion"] + " with sdkmanager")
    WORK.mkdir(parents=True, exist_ok=True)
    flat = checkout("flatbuffers", "https://github.com/google/flatbuffers", LOCK["flatbuffersCommit"])
    dex = checkout("DexKit", "https://github.com/LuckyPray/DexKit", LOCK["dexkitCommit"])
    env = dict(os.environ, JAVA_HOME=str(java_home), ANDROID_HOME=str(sdk),
               PATH=str(java_home / "bin") + os.pathsep + os.environ["PATH"],
               CMAKE_BUILD_PARALLEL_LEVEL="8",
               GRADLE_USER_HOME=os.environ.get("DEXKIT_BUILD_GRADLE_HOME", str(Path.home() / ".gradle")))
    run(["cmake", "-S", flat, "-B", WORK / "flat-build", "-G", "Ninja",
         "-DCMAKE_BUILD_TYPE=Release", "-DFLATBUFFERS_BUILD_TESTS=OFF",
         "-DFLATBUFFERS_BUILD_FLATC=ON", "-DFLATBUFFERS_BUILD_FLATHASH=OFF"], env=env)
    run(["cmake", "--build", WORK / "flat-build", "--target", "flatc", "-j", "8"], env=env)
    flatc = WORK / "flat-build/flatc"
    expected = "flatc version " + LOCK["flatbuffersVersion"]
    if subprocess.check_output([str(flatc), "--version"], text=True).strip() != expected:
        raise RuntimeError("FlatBuffers compiler version mismatch")
    run(["mvn", "-B", "test"], cwd=flat / "java", env=env)
    flat_tests = tests(flat / "java/target/surefire-reports")

    classes = WORK / "flat-java-classes"
    classes.mkdir(exist_ok=True)
    java_sources = sorted((flat / "java/src/main/java").rglob("*.java"))
    run([java_home / "bin/javac", "--release", "8", "-d", classes, *java_sources], env=env)
    license_dir = classes / "META-INF"
    license_dir.mkdir(exist_ok=True)
    shutil.copyfile(flat / "LICENSE", license_dir / "LICENSE-flatbuffers")
    flat_version = LOCK["flatbuffersVersion"]
    flat_dir = REPOSITORY / "com/google/flatbuffers/flatbuffers-java" / flat_version
    flat_dir.mkdir(parents=True, exist_ok=True)
    flat_jar = flat_dir / ("flatbuffers-java-" + flat_version + ".jar")
    staged_jar = WORK / "flatbuffers-java.jar"
    run([java_home / "bin/jar", "--create", "--file", staged_jar,
         "--date=2026-02-06T00:00:00Z", "-C", classes, "."], env=env)
    publish_file(staged_jar, flat_jar)
    flat_pom = flat_dir / ("flatbuffers-java-" + flat_version + ".pom")
    flat_pom.write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>com.google.flatbuffers</groupId>"
        "<artifactId>flatbuffers-java</artifactId><version>" + flat_version +
        "</version><packaging>jar</packaging></project>\n"
    )
    patch(dex / "schema/gen_code.py", 'FLATC_VERSION = "23.5.26"',
          'FLATC_VERSION = "' + flat_version + '"')
    run([sys.executable, dex / "schema/gen_code.py"], cwd=dex,
        env=dict(env, FLATC=str(flatc)))
    headers = dex / "Core/third_party/flatbuffers/include"
    if not headers.resolve().is_relative_to(dex.resolve()) or headers.is_symlink():
        raise RuntimeError("Unsafe FlatBuffers include path")
    if headers.exists():
        shutil.rmtree(headers)
    shutil.copytree(flat / "include", headers)

    for filename in ("dexkit/build.gradle", "dexkit-android/build.gradle"):
        patch(dex / filename, "flatbuffers-java:23.5.26", "flatbuffers-java:" + flat_version)
    jvm_gradle = dex / "dexkit/build.gradle"
    text = jvm_gradle.read_text()
    native_input = '    inputs.file(layout.buildDirectory.file("library/libdexkit.so"))'
    if native_input not in text:
        text = text.replace("test {\n", "test {\n" + native_input + "\n", 1)
        jvm_gradle.write_text(text)
    android_gradle = dex / "dexkit-android/build.gradle"
    patch(android_gradle, 'ndkVersion = "26.1.10909125"', 'ndkVersion = "' + LOCK["ndkVersion"] + '"')
    patch(android_gradle, "-DANDROID_STL=none", "-DANDROID_STL=c++_static")
    text = android_gradle.read_text()
    if 'ndk { abiFilters "arm64-v8a" }' not in text:
        text = text.replace("        minSdk = 21", '        minSdk = 21\n        ndk { abiFilters "arm64-v8a" }')
        android_gradle.write_text(text)
    native = dex / "dexkit-android/CMakeLists.txt"
    native.write_text(native.read_text().replace("find_package(cxx REQUIRED CONFIG)", "")
                      .replace("link_libraries(cxx::cxx)", ""))
    for filename in ("Core/CMakeLists.txt", "dexkit/src/main/cpp/CMakeLists.txt",
                     "dexkit-android/CMakeLists.txt"):
        file = dex / filename
        file.write_text(file.read_text().replace("VERSION 3.4.1", "VERSION 3.5"))
    settings = dex / "settings.gradle"
    repository_line = '        maven { url = uri("' + str(REPOSITORY) + '") }'
    text = settings.read_text()
    if repository_line not in text:
        text = text.replace("    repositories {\n        google()",
                            "    repositories {\n" + repository_line + "\n        google()", 1)
        settings.write_text(text)
    (dex / "local.properties").write_text("sdk.dir=" + str(sdk) + "\n")

    run(["cmake", "-S", dex / "dexkit/src/main/cpp", "-B", WORK / "host-native",
         "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_C_COMPILER=clang",
         "-DCMAKE_CXX_COMPILER=clang++"], env=env)
    run(["cmake", "--build", WORK / "host-native", "-j", "8"], env=env)
    run(["bash", "./gradlew", "--no-daemon", "--max-workers=8", ":dexkit:jar",
         ":dexkit:test", ":dexkit-android:assembleRelease"], cwd=dex, env=env)
    dex_tests = tests(dex / "dexkit/build/test-results/test")
    dex_dir = REPOSITORY / "org/luckypray/dexkit" / VERSION
    aar = dex_dir / ("dexkit-" + VERSION + ".aar")
    publish_file(dex / "dexkit-android/build/outputs/aar/dexkit-android-release.aar", aar)
    pom = aar.with_suffix(".pom")
    pom.write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>org.luckypray</groupId>"
        "<artifactId>dexkit</artifactId><version>" + VERSION +
        "</version><packaging>aar</packaging><dependencies><dependency>"
        "<groupId>com.google.flatbuffers</groupId><artifactId>flatbuffers-java</artifactId><version>" +
        flat_version + "</version></dependency><dependency><groupId>org.jetbrains.kotlin</groupId>"
        "<artifactId>kotlin-stdlib</artifactId><version>1.9.20</version></dependency>"
        "</dependencies></project>\n"
    )
    desktop = DESKTOP / "libdexkit.so"
    publish_file(WORK / "host-native/libdexkit.so", desktop)
    artifacts = {str(file): digest(file) for file in (aar, pom, flat_jar, flat_pom, desktop)}
    receipt = dict(recipe=RECIPE, lock=LOCK, repository=str(REPOSITORY), artifacts=artifacts,
                   flatbuffersTests=flat_tests, dexkitTests=dex_tests)
    temporary = RECEIPT.with_suffix(".tmp")
    temporary.write_text(json.dumps(receipt, indent=2) + "\n")
    temporary.replace(RECEIPT)
    print("DexKit source build verified: " + VERSION, flush=True)


def verify_apk(apk):
    source_aar = REPOSITORY / "org/luckypray/dexkit" / VERSION / ("dexkit-" + VERSION + ".aar")
    with zipfile.ZipFile(source_aar) as archive:
        expected = archive.read("jni/arm64-v8a/libdexkit.so")
    with zipfile.ZipFile(apk) as archive:
        actual = archive.read("lib/arm64-v8a/libdexkit.so")
    if hashlib.sha256(actual).digest() != hashlib.sha256(expected).digest():
        raise RuntimeError("APK DexKit native library differs from the pinned source AAR")
    print("APK DexKit native library matches the source build: " + VERSION)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-apk", type=Path)
    arguments = parser.parse_args()
    BASE.mkdir(parents=True, exist_ok=True)
    with (BASE / (VERSION + ".lock")).open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if cached():
            print("Verified cached DexKit: " + VERSION)
        else:
            build()
        if arguments.verify_apk is not None:
            verify_apk(arguments.verify_apk)


if __name__ == "__main__":
    main()
