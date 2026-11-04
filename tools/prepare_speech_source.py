#!/usr/bin/env python3
"""Plan or stage pinned speech sources. Network requires --download and prior user approval."""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,tarfile,zipfile,urllib.request,os,uuid,contextlib
PROJECT=Path(__file__).resolve().parents[1]
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  while block:=stream.read(1048576):h.update(block)
 return h.hexdigest()
@contextlib.contextmanager
def budget_lock(ledger):
 lock=ledger.with_name(ledger.name+'.lock')
 with lock.open('a+b') as handle:
  if lock.stat().st_size==0:handle.write(b'0');handle.flush();os.fsync(handle.fileno())
  handle.seek(0)
  if os.name=='nt':
   import msvcrt
   try:msvcrt.locking(handle.fileno(),msvcrt.LK_NBLCK,1)
   except OSError as error:raise RuntimeError('Another budgeted download is active; do not run parallel fetches') from error
   try:yield
   finally:handle.seek(0);msvcrt.locking(handle.fileno(),msvcrt.LK_UNLCK,1)
  else:
   import fcntl
   try:fcntl.flock(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
   except OSError as error:raise RuntimeError('Another budgeted download is active; do not run parallel fetches') from error
   try:yield
   finally:fcntl.flock(handle,fcntl.LOCK_UN)

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--cache',type=Path);parser.add_argument('--workspace',type=Path)
 parser.add_argument('--download',action='store_true',help='Use only after the user approves these downloads and source execution')
 parser.add_argument('--model',action='store_true');parser.add_argument('--windows',action='store_true')
 parser.add_argument('--stage',action='store_true',help='Extract verified cached archives into a new workspace')
 parser.add_argument('--budget-ledger',type=Path,help='Append-only shared task download budget ledger')
 parser.add_argument('--download-limit-bytes',type=int,default=1073741824)
 args=parser.parse_args();meta=json.loads((PROJECT/'docs/speech-dependencies.json').read_text());model=json.loads((PROJECT/'docs/voice-model-source.json').read_text())
 entries=[dict(meta['sherpa-source'],target='source'),*[dict(item,target='deps') for item in meta['sources']],dict(meta['onnx-android'],target='ort-android'),dict(meta['sherpa-java-bindings'],target=None)]
 if args.windows:entries.append(dict(meta['sherpa-windows-asr'],target='windows-runtime'))
 if args.model:entries.append({'url':model['source'],'archive':model['source'].rsplit('/',1)[1],'sha256':model['sha256'],'bytes':model['bytes'],'target':'models'})
 report={'mode':'download-and-stage' if args.download else 'stage-existing' if args.stage else 'plan-only','downloads_bytes':sum(item['bytes'] for item in entries),'entries':entries,'network_requires_prior_user_approval':True,'does_not_install_apks_or_change_security':True}
 print(json.dumps(report,indent=2),flush=True)
 if not args.download and not args.stage:return 0
 if args.cache is None or args.workspace is None:parser.error('--cache and --workspace are required for staging')
 cache=args.cache.expanduser().resolve();work=args.workspace.expanduser().resolve()
 if work.exists():parser.error('Workspace already exists; choose a new path to preserve all earlier files')
 if args.download_limit_bytes<1 or args.download_limit_bytes>1073741824:parser.error('Download ceiling must not exceed approved 1 GiB')
 cache.mkdir(parents=True,exist_ok=True);work.mkdir(parents=True,exist_ok=False)
 ledger=(args.budget_ledger or cache/'download-budget.jsonl').resolve();ledger.parent.mkdir(parents=True,exist_ok=True)
 # Reserve before every network read. An interrupted read remains charged in full,
 # so termination cannot make a later retry silently exceed the user's total cap.
 with budget_lock(ledger):
  reserved=0
  if ledger.exists():
   for line in ledger.read_text().splitlines():
    event=json.loads(line)
    if event.get('kind')=='reserve':
     if type(event.get('bytes')) is not int or event['bytes']<0:raise RuntimeError('Invalid budget ledger; stop and preserve it')
     reserved+=event['bytes']
  def record(event):
   with ledger.open('a',encoding='utf-8') as stream:stream.write(json.dumps(event)+'\n');stream.flush();os.fsync(stream.fileno())
  def reserve(size,archive):
   nonlocal reserved
   if reserved+size>args.download_limit_bytes:raise RuntimeError('Approved cumulative download budget exhausted; ask user before any expansion')
   record({'kind':'reserve','bytes':size,'archive':archive});reserved+=size
  missing=sum(item['bytes'] for item in entries if not (cache/item['archive']).exists())
  if args.download and reserved+missing>args.download_limit_bytes:raise RuntimeError('Planned downloads exceed remaining approved cumulative budget; no network started')
  receipt=[]
  def checked_target(base,name):
   if '\\' in name or ':' in name:raise ValueError('Unsafe archive path')
   parts=PurePosixPath(name)
   if parts.is_absolute() or '..' in parts.parts:raise ValueError('Unsafe archive path')
   target=(base/Path(*parts.parts)).resolve()
   if target!=base and base not in target.parents:raise ValueError('Unsafe archive target')
   return target
  for item in entries:
   path=cache/item['archive']
   if not path.exists():
    if not args.download:raise SystemExit('Missing pinned archive '+str(path)+'; nothing downloaded. Prior user approval is required before --download.')
    partial=path.with_name(path.name+'.partial-'+uuid.uuid4().hex)
    with partial.open('xb') as out,urllib.request.urlopen(item['url'],timeout=60) as response:
     length=response.headers.get('Content-Length')
     if length is not None and int(length)!=item['bytes']:raise RuntimeError('Unexpected HTTP length; file retained, body not read')
     received=0
     while received<item['bytes']:
      size=min(1048576,item['bytes']-received);reserve(size,item['archive']);block=response.read(size)
      if not block:raise RuntimeError('Truncated download; partial retained and reserved budget remains charged')
      out.write(block);received+=len(block)
     record({'kind':'asset_received','archive':item['archive'],'bytes':received,'reserved_total':reserved})
    if partial.stat().st_size!=item['bytes'] or digest(partial)!=item['sha256']:raise RuntimeError('Downloaded archive mismatch; partial retained: '+str(partial))
    partial.rename(path)
   if path.stat().st_size!=item['bytes'] or digest(path)!=item['sha256']:raise RuntimeError('Pinned archive mismatch: '+str(path))
   if item['target']:
    destination=work/item['target'];destination.mkdir(exist_ok=True)
    if zipfile.is_zipfile(path):
     with zipfile.ZipFile(path) as archive:
      if sum(member.file_size for member in archive.infolist())>2**32:raise RuntimeError('Archive expands beyond limit')
      for member in archive.infolist():
       target=checked_target(destination,member.filename)
       if (member.external_attr>>16)&0o170000==0o120000:raise RuntimeError('Archive symlink refused')
       if member.is_dir():target.mkdir(parents=True,exist_ok=True);continue
       target.parent.mkdir(parents=True,exist_ok=True)
       with archive.open(member) as src,target.open('xb') as out:
        while block:=src.read(1048576):out.write(block)
    else:
     with tarfile.open(path) as archive:
      members=archive.getmembers()
      if sum(member.size for member in members if member.isfile())>2**32:raise RuntimeError('Archive expands beyond limit')
      for member in members:
       target=checked_target(destination,member.name)
       if member.isdir():target.mkdir(parents=True,exist_ok=True);continue
       # Upstream includes unrelated example symlinks; preserve archive but do not materialize links.
       if not member.isfile():continue
       target.parent.mkdir(parents=True,exist_ok=True)
       with archive.extractfile(member) as src,target.open('xb') as out:
        while block:=src.read(1048576):out.write(block)
   receipt.append({'archive':str(path),'bytes':path.stat().st_size,'sha256':item['sha256'],'target':item['target']})
  trees={str(path.relative_to(work)):digest(path) for group in ['source','deps','ort-android'] for path in sorted((work/group).rglob('*')) if path.is_file()}
  (work/'SPEECH-SOURCE-RECEIPT.json').write_text(json.dumps({'source_lock_sha256':digest(PROJECT/'docs/speech-dependencies.json'),'model_lock_sha256':digest(PROJECT/'docs/voice-model-source.json'),'archives':receipt,'staged_source_files':trees,'download_budget_ledger':str(ledger),'charged_download_bytes':reserved,'approved_download_limit_bytes':args.download_limit_bytes,'built':False},indent=2)+'\n')
  print('Pinned archives verified and staged. No build, package installation, device operation or model activation performed.')
  return 0
if __name__=='__main__':raise SystemExit(main())
