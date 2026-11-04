"""Repair paths in our extracted Debian Wine overlay only; no system install or auth changes."""
from pathlib import Path
import shutil

repo=Path(__file__).resolve().parents[3]
wine=repo/'.tools/wine'
server=wine/'usr/lib/wine/wineserver'
backup=server.with_name('wineserver.vendor-original')
if not backup.exists():shutil.copyfile(server,backup)
server.write_text('''#!/bin/sh
set -e
WANGSHU_SERVER_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$WANGSHU_SERVER_DIR/wineserver64" -p0 "$@"
''')
server.chmod(0o755)
mirror=wine/'usr/share/wine/wine';mirror.mkdir(exist_ok=True)
if not (mirror/'nls').exists():(mirror/'nls').symlink_to('../nls',target_is_directory=True)
prefix=repo/'.tools/wine-test-prefix-v3/drive_c/windows'
for bits,triplet,directory in [('x64','x86_64-w64-mingw32','system32'),('x86','i686-w64-mingw32','syswow64')]:
    dest=prefix/directory;dest.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(wine/('usr/'+triplet+'/lib/zlib1.dll'),dest/'zlib1.dll')
sources=wine/'usr/lib/i386-linux-gnu/wine/i386-windows'
if sources.is_dir():
    for source in sources.iterdir():
        target=prefix/'syswow64'/source.name
        if source.is_file() and not target.exists():target.symlink_to(source.resolve())
print('PASS local Wine overlay paths prepared; no Windows integration claim')
