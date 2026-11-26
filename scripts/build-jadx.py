#!/usr/bin/env python3
"""把固定 jadx 发布源码构建到本地依赖仓库。"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CONFIG_FILE = ROOT / "gradle/jadx-source.json"
CONFIG = json.loads(CONFIG_FILE.read_text())
BASE = Path(os.environ.get("DEXKIT_SOURCE_HOME", Path.home() / "tools/dexkit-source")).resolve()
REPOSITORY = Path(os.environ.get("DEXKIT_MAVEN_REPO", BASE / "maven")).resolve()
SOURCE = Path(os.environ.get("DEXADAPT_JADX_BUILD_DIR", BASE / "jadx" / CONFIG["version"])).resolve()
RECEIPT = BASE / ("jadx-" + CONFIG["version"] + ".json")
RECIPE = hashlib.sha256(Path(__file__).read_bytes() + CONFIG_FILE.read_bytes()).hexdigest()
MODULES = {
    "jadx-input-api": ":jadx-plugins:jadx-input-api",
    "jadx-zip": ":jadx-commons:jadx-zip",
    "jadx-core": ":jadx-core",
    "jadx-dex-input": ":jadx-plugins:jadx-dex-input",
}


def sha(path):
    with Path(path).open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def ready():
    try:
        record = json.loads(RECEIPT.read_text())
        return (
            record["recipe"] == RECIPE and record["source"] == CONFIG
            and record["repository"] == str(REPOSITORY)
            and len(record["artifacts"]) >= len(MODULES) * 2
            and all(Path(p).is_file() and sha(p) == value for p, value in record["artifacts"].items())
        )
    except (OSError, ValueError, KeyError, TypeError):
        return False


def run(arguments, **kwargs):
    subprocess.run([str(arg) for arg in arguments], check=True, **kwargs)


def build():
    if not SOURCE.exists():
        SOURCE.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--no-checkout", "--depth", "1", "https://github.com/skylot/jadx", SOURCE])
        run(["git", "-C", SOURCE, "fetch", "--depth", "1", "origin", CONFIG["commit"]])
        run(["git", "-C", SOURCE, "checkout", "--detach", "FETCH_HEAD"])
    actual = subprocess.check_output(["git", "-C", SOURCE, "rev-parse", "HEAD"], text=True).strip()
    if actual != CONFIG["commit"]:
        raise RuntimeError("jadx source commit mismatch")
    if subprocess.check_output(["git", "-C", SOURCE, "status", "--porcelain"], text=True).strip():
        raise RuntimeError("jadx source checkout has local changes")
    # Upstream prints JADX_* environment values. Supply only the public build settings.
    env = {key: value for key, value in os.environ.items() if not key.startswith("JADX_")}
    env.update(JADX_VERSION=CONFIG["version"], JADX_BUILD_JAVA_VERSION="17")
    tasks = [module + ":publishToMavenLocal" for module in MODULES.values()]
    jdk17 = Path(os.environ.get("DEXKIT_BUILD_JAVA_HOME", Path.home() / "tools/temurin17-dexkit"))
    env.update(JAVA_HOME=str(jdk17), PATH=str(jdk17 / "bin") + os.pathsep + os.environ["PATH"])
    run(["bash", "./gradlew", "--no-daemon", "--max-workers=8",
         "-Dmaven.repo.local=" + str(REPOSITORY),
         "-Dorg.gradle.java.installations.paths=" + str(jdk17), *tasks],
        cwd=SOURCE, env=env)
    artifacts = {}
    for name in MODULES:
        folder = REPOSITORY / "io/github/skylot" / name / CONFIG["version"]
        for extension in ("jar", "pom", "module"):
            file = folder / (name + "-" + CONFIG["version"] + "." + extension)
            if extension == "module" and not file.exists():
                continue
            if not file.is_file():
                raise RuntimeError("Missing jadx output " + str(file))
            artifacts[str(file)] = sha(file)
    record = dict(recipe=RECIPE, source=CONFIG, repository=str(REPOSITORY), artifacts=artifacts)
    temporary = RECEIPT.with_suffix(".tmp")
    temporary.write_text(json.dumps(record, indent=2) + "\n")
    temporary.replace(RECEIPT)
    print("Verified jadx source modules " + CONFIG["version"], flush=True)


def main():
    BASE.mkdir(parents=True, exist_ok=True)
    with (BASE / ("jadx-" + CONFIG["version"] + ".lock")).open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if ready():
            print("Verified cached jadx " + CONFIG["version"])
        else:
            build()


if __name__ == "__main__":
    main()
