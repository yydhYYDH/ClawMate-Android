"""Export a pinned SDK and compiled HAP, replacing only owned generated runtime trees."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import zipfile


def reject_links(root):
    if not root.exists() and not root.is_symlink():
        return
    info = root.lstat()
    if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0):
        raise ValueError("Runtime tree must not contain links: " + str(root))
    if root.is_dir():
        for child in root.iterdir():
            reject_links(child)


def check_output_path(target, allowed):
    if not target.resolve().is_relative_to(allowed) or target.resolve() == allowed:
        raise ValueError("Runtime output must remain inside the ignored workspace")
    for parent in [target, *target.parents]:
        if parent == allowed:
            break
        if parent.is_symlink() or (parent.exists() and getattr(parent.lstat(), "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)):
            raise ValueError("Runtime output parent must not be a link")


def export_runtime(project, sdk, app):
    project, sdk = Path(project).resolve(), Path(sdk).resolve()
    allowed = next((p for p in project.parents if p.name == ".arkui-x-validation"), None)
    app = Path(app).absolute()
    if allowed is None or not app.resolve().is_relative_to(allowed) or app.resolve() == allowed:
        raise ValueError("Runtime output must remain inside the ignored workspace")
    check_output_path(app, allowed)
    for source in (project, sdk):
        default_probe = source == project and app.resolve() == project / '.arkui-x/android/app'
        if source.is_relative_to(app.resolve()) or (app.resolve().is_relative_to(source) and not default_probe):
            raise ValueError("Runtime output must not overlap SDK or HAP source")
    assets, libs = app / "src/main/assets/arkui-x", app / "libs"
    for owned in (assets, libs):
        check_output_path(owned, allowed)
        reject_links(owned)
        if owned.exists() and not owned.is_dir():
            raise ValueError("Owned runtime output must be a directory")
    hap = project / "entry/build/default/outputs/default/entry-default-unsigned.hap"
    reject_links(hap)
    libraries = {}
    for folder in ("engine/lib", "plugins/api/lib", "plugins/component/lib"):
        source_root = sdk / folder
        if not source_root.is_dir():
            # API20 ships component/apiConfig.json without a component/lib tree.
            if folder == 'plugins/component/lib':
                continue
            raise ValueError("SDK directory missing: " + folder)
        for source in source_root.rglob("*"):
            if not source.is_file():
                continue
            target = source.name if source.suffix == ".jar" else "arm64-v8a/" + source.name if source.suffix == ".so" and "android-arm64-release" in source.parts else None
            if target is None:
                continue
            if source.is_symlink():
                raise ValueError("SDK library must not be a link")
            previous = libraries.get(target)
            if previous and hashlib.sha256(previous.read_bytes()).digest() != hashlib.sha256(source.read_bytes()).digest():
                raise ValueError("SDK contains conflicting library names: " + source.name)
            libraries[target] = source
    if not libraries or not (sdk / "engine/systemres").is_dir() or not (sdk / "arkui-x.json").is_file():
        raise ValueError("SDK runtime is incomplete")
    reject_links(sdk / "engine/systemres")
    reject_links(sdk / "arkui-x.json")
    # Validate the complete archive before creating output directories.
    with zipfile.ZipFile(hap) as archive:
        members = archive.infolist()
        if len(members) > 10000 or sum(m.file_size for m in members) > 512 * 1024 * 1024:
            raise ValueError("HAP exceeds export bounds")
        seen = set()
        for member in members:
            name = PurePosixPath(member.filename)
            if name.is_absolute() or ".." in name.parts or "\\" in member.filename or ":" in member.filename or stat.S_ISLNK(member.external_attr >> 16):
                raise ValueError("HAP path escapes or links outside the isolated asset directory")
            key = str(name).casefold()
            if key in seen:
                raise ValueError("Conflicting HAP resource paths")
            seen.add(key)
        app.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".runtime-export-", dir=app.parent) as temp:
            staging = Path(temp)
            staged_assets, staged_libs = staging / "assets", staging / "libs"
            entry = staged_assets / "entry"
            entry.mkdir(parents=True)
            archive.extractall(entry)
            shutil.copytree(sdk / "engine/systemres", staged_assets / "systemres")
            shutil.copy2(sdk / "arkui-x.json", staged_assets / "arkui-x.json")
            staged_libs.mkdir()
            for relative, source in libraries.items():
                target = staged_libs / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            # Everything was checked/copied successfully before replacing owned
            # assets/libs. A later interrupted export still fails the runtime seal.
            for target, prepared in ((assets, staged_assets), (libs, staged_libs)):
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists():
                    shutil.rmtree(target)
                os.replace(prepared, target)
    return {"hap": str(hap), "exportedLibraries": len(libraries), "abi": "arm64-v8a"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True, type=Path)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--android-app", type=Path)
    args = parser.parse_args()
    print(export_runtime(args.project, args.sdk, args.android_app or args.project / ".arkui-x/android/app"))
