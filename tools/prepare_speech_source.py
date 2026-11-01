"""Restore pinned public speech dependencies. Downloads require --download and authorization."""
from pathlib import Path
from datetime import datetime,timezone
import argparse,hashlib,json,tarfile,zipfile,urllib.request
repo=Path(__file__).resolve().parents[3];project=repo/'projects/input-method'
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--download',action='store_true');parser.add_argument('--model',action='store_true');args=parser.parse_args()
meta=json.loads((project/'docs/speech-dependencies.json').read_text());downloads=repo/'.tools/downloads';downloads.mkdir(parents=True,exist_ok=True)
def get(entry,folder=downloads):
 p=folder/entry['archive']
 if not p.exists():
  if not args.download:raise SystemExit('Missing '+str(p)+'; obtain installation approval then use --download')
  if datetime.now(timezone.utc)>=datetime(2026,10,23,15,5,52,tzinfo=timezone.utc):raise SystemExit('Recorded installation authorization expired; require renewed scope before new downloads')
  partial=p.with_suffix(p.suffix+'.part')
  with urllib.request.urlopen(entry['url'],timeout=50) as response,partial.open('wb') as out:
   while chunk:=response.read(1048576):out.write(chunk)
  if hashlib.sha256(partial.read_bytes()).hexdigest()!=entry['sha256']:raise RuntimeError('Downloaded archive hash mismatch: '+str(partial))
  partial.rename(p)
 if hashlib.sha256(p.read_bytes()).hexdigest()!=entry['sha256']:raise RuntimeError('Archive hash mismatch '+str(p))
 return p
def extract(p,dest):
 dest.mkdir(parents=True,exist_ok=True)
 if zipfile.is_zipfile(p):
  with zipfile.ZipFile(p) as z:
   for name in z.namelist():
    if name.startswith('/') or '..' in Path(name).parts:raise RuntimeError('Unsafe ZIP member')
   z.extractall(dest)
 else:
  with tarfile.open(p) as t:
   # The upstream tree contains unrelated absolute Go example symlinks; never materialize links.
   t.extractall(dest,members=[m for m in t.getmembers() if m.isfile() or m.isdir()],filter='data')
source=get(meta['sherpa-source']);extract(source,repo/'.tools/sherpa/source');root=repo/'.tools/sherpa/source/sherpa-onnx-1.13.6'
for entry in meta['sources']:
 path=get(entry,root);extract(path,repo/'.tools/sherpa/deps')
extract(get(meta['onnx-android']),repo/'.tools/sherpa/ort-android')
get(meta['sherpa-java-bindings']);extract(get(meta['sherpa-windows-asr']),repo/'.tools/sherpa')
if args.model:
 d=json.loads((project/'docs/voice-model-source.json').read_text());entry={'url':d['source'],'archive':d['source'].rsplit('/',1)[1],'sha256':d['sha256']};extract(get(entry),repo/'.tools/models')
print('Pinned speech source/dependencies verified and restored; no private documents or credentials used')
