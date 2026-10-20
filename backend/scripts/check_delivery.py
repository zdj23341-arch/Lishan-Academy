"""Read-only source delivery checks; no database or provider access."""
from pathlib import Path
import ast
import json
import re
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]


def main():
    scripts = list((ROOT / 'scripts').glob('*.py'))
    for file in scripts:
        ast.parse(file.read_text(encoding='utf-8-sig'), filename=str(file))
    versions = []
    for file in (ROOT / 'src/main/resources/db/migration').glob('V*__*.sql'):
        match = re.fullmatch(r'V(\d+)__.+\.sql', file.name)
        assert match, f'Unexpected migration filename: {file.name}'
        versions.append(int(match.group(1)))
    assert versions and len(versions) == len(set(versions)), 'Missing or duplicate migration versions'
    docs = [ROOT / 'README.md', *sorted((ROOT / 'docs').rglob('*.md'))]
    links = 0
    for file in docs:
        for target in re.findall(r'\]\(([^)]+)\)', file.read_text(encoding='utf-8')):
            target = unquote(target.split('#')[0]).strip('<>')
            if not target or re.match(r'[a-zA-Z][a-zA-Z0-9+.-]*:', target):
                continue
            assert (file.parent / target).exists(), f'Broken link: {file.name} -> {target}'
            links += 1
    assert (ROOT / 'docs/clamd.conf.example').is_file()
    assert (ROOT / 'docs/wechat-configuration.example.yml').is_file()
    print(json.dumps({'status': 'PASS', 'pythonScripts': len(scripts),
                      'migrations': len(versions), 'latestMigration': max(versions),
                      'markdownLinks': links,
                      'scope': 'syntax, migration filenames and local document/config paths only'}))


if __name__ == '__main__':
    main()
