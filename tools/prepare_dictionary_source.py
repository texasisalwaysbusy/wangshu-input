"""Restore pinned upstream data only; never execute source-package setup code."""
import argparse
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request

root=Path(__file__).resolve().parents[1]
repo=root.parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--download',action='store_true')
args=parser.parse_args()
deadline=datetime(2026,10,23,15,5,52,tzinfo=timezone.utc)

def asset(url,sha,name):
    target=repo/'.tools/downloads'/name
    if not target.is_file():
        if not args.download:raise SystemExit('Missing cached '+name+'; explicit --download and applicable authorization required')
        if datetime.now(timezone.utc)>=deadline:raise SystemExit('Recorded installation authorization expired; obtain renewed scope before downloading')
        target.parent.mkdir(parents=True,exist_ok=True)
        with urllib.request.urlopen(url,timeout=60) as src,target.open('wb') as out:
            while data:=src.read(1048576):out.write(data)
    if hashlib.sha256(target.read_bytes()).hexdigest()!=sha:raise SystemExit('SHA-256 mismatch: '+name)
    return target

cedict=json.loads((root/'docs/dictionary-source.json').read_text())
frequency=json.loads((root/'docs/frequency-source.json').read_text())
version=json.loads((root/'data/version.json').read_text())
for metadata,archive_name,member,directory,filename,expected in [
    (cedict,'cc-cedict_0.0~repack20250329.orig.tar.xz','cedict_ts.u8','cedict-source','cedict_ts.u8',version['upstream_sha256']),
    (frequency,'jieba-0.42.1.tar.gz','jieba-0.42.1/jieba/dict.txt','jieba-source','dict.txt',frequency['dictionary_sha256']),
]:
    archive=asset(metadata['source'],metadata.get('archive_sha256',metadata.get('sha256')),archive_name)
    with tarfile.open(archive) as source:
        entry=source.getmember(member)
        if not entry.isfile() or entry.size>32*1024*1024:raise SystemExit('Invalid dictionary member')
        data=source.extractfile(entry).read()
    if hashlib.sha256(data).hexdigest()!=expected:raise SystemExit('Member SHA-256 mismatch')
    dest=repo/'.tools'/directory;dest.mkdir(parents=True,exist_ok=True);(dest/filename).write_bytes(data)
    print('PASS verified upstream data',filename)
