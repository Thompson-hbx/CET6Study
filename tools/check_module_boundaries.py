"""Verify real Gradle module boundaries without resolving or changing dependencies."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
names = set()
for declaration in re.findall(r'include\((.*?)\)', settings):
    names.update(re.findall(r'"(:[^"]+)"', declaration))
locations = dict(re.findall(r'project\("(:[^"]+)"\)\.projectDir = file\("([^"]+)"\)', settings))
modules = {name: ROOT / locations.get(name, name[1:]) for name in names}
graph = {}
facades = {}
for name, directory in modules.items():
    source = (directory / "build.gradle.kts").read_text(encoding="utf-8")
    graph[name] = set(re.findall(r'project\("(:[^"]+)"\)', source))
    assert graph[name] <= names, f"Unknown module dependency in {name}"
    if name not in {":app", ":desktop"}:
        assert not graph[name] & {":app", ":desktop"}, f"Feature depends on a host: {name}"
    for source_dir in [directory / "src/main/java", directory / "src/main/kotlin"]:
        for path in source_dir.rglob("*.kt"):
            package = re.search(r'^package ([\w.]+)', path.read_text(encoding="utf-8"), re.M)
            if package:
                key = (package.group(1), path.stem)
                assert key not in facades, f"Duplicate Kotlin file facade: {path} and {facades.get(key)}"
                facades[key] = path

visited, active = set(), set()
def visit(name):
    assert name not in active, f"Cyclic module dependency at {name}"
    if name in visited:
        return
    active.add(name)
    for dependency in graph[name]:
        visit(dependency)
    active.remove(name)
    visited.add(name)
for name in names:
    visit(name)

for host in ["app", "desktop"]:
    for path in (ROOT / host / "src/main").rglob("*.kt"):
        assert "@Composable" not in path.read_text(encoding="utf-8"), f"Feature UI left in host: {path}"
assert not graph[":core-ui"] & {":core-study", ":ui-state"}, "Visual components must not depend on business or ViewModel layers"
assert not graph[":core-study"] & {name for name in names if name.startswith(":feature-") or name in {":core-ui", ":ui-state", ":ui-navigation"}}, "Business layer depends on UI"
print(f"PASS: {len(modules)} modules; acyclic dependencies; no feature-to-host dependencies, host feature UI or duplicate Kotlin file facades.")
