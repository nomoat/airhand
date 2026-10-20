#!/usr/bin/env python3
"""Export an explicit, credential-free set of sources for open-source review."""
import argparse
import re
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
root_files = ('README.md', 'PRIVACY.md', 'TESTING.md', 'NOTICE', 'LICENSE', '.gitignore',
              'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat',
              'app/build.gradle.kts')
paths = [root / name for name in root_files if (root / name).is_file()]
for folder in ('app/src', 'gradle/wrapper', 'scripts'):
    paths += [p for p in (root / folder).rglob('*') if p.is_file() and not p.is_symlink()]
paths = [p for p in paths if '__pycache__' not in p.parts and p.suffix != '.pyc']
for p in paths:
    if p.suffix in ('.kt', '.kts', '.xml', '.md', '.py', '.sh', '.properties') or p.name in ('NOTICE', 'LICENSE'):
        data = p.read_text()
        if re.search(r'rbk_[A-Za-z0-9_-]{20,}', data):
            raise SystemExit(f'Possible API key in {p.relative_to(root)}; export stopped.')
        if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', data):
            raise SystemExit(f'Private key in {p.relative_to(root)}; export stopped.')
version_match = re.search(r'versionName\s*=\s*"([^"]+)"', (root / 'app/build.gradle.kts').read_text())
assert version_match, 'Version name missing'
args.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(set(paths)):
        archive.write(path, f'AirHand-{version_match.group(1)}/{path.relative_to(root)}')
print(f'Exported {len(set(paths))} source files to {args.output}')
