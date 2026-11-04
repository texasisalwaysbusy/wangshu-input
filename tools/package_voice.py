#!/usr/bin/env python3
"""Package already-verified local model files. No download, installation, activation or upload."""
from pathlib import Path
import argparse,hashlib,json,shutil,zipfile
PROJECT=Path(__file__).resolve().parents[1]
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  while block:=stream.read(1048576):h.update(block)
 return h.hexdigest()
def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--models',required=True,type=Path);parser.add_argument('--output',required=True,type=Path)
 args=parser.parse_args();base=args.models.resolve();output=args.output.resolve();receipt=output.with_name(output.name+'.receipt.json');meta=json.loads((PROJECT/'docs/voice-model-source.json').read_text())
 if output.exists() or receipt.exists():parser.error('Output already exists; choose a new path to preserve earlier files')
 selected=[]
 for name,entry in meta['selected_files'].items():
  path=base/entry['source_member']
  if not path.is_file() or path.stat().st_size!=entry['bytes'] or digest(path)!=entry['sha256']:raise RuntimeError('Pinned model mismatch: '+str(path))
  selected.append((name,path,entry))
 output.parent.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(output,'x',zipfile.ZIP_DEFLATED,compresslevel=4) as archive:
  for name,path,entry in selected:
   info=zipfile.ZipInfo(name,(2023,2,20,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
   with path.open('rb') as src,archive.open(info,'w') as out:shutil.copyfileobj(src,out,1048576)
  notice=zipfile.ZipInfo('NOTICE.txt',(2023,2,20,0,0,0));notice.compress_type=zipfile.ZIP_DEFLATED;notice.external_attr=0o100644<<16
  archive.writestr(notice,'Wangshu optional offline bilingual voice model\nApache-2.0\nSource: '+meta['source']+'\nModel card: '+meta['model_card']+'\n\n'+(PROJECT/'data/SHERPA-ONNX-LICENSE.txt').read_text())
 with zipfile.ZipFile(output) as archive:
  if archive.testzip() is not None:raise RuntimeError('Model archive CRC mismatch; output retained')
  for name,path,entry in selected:
   h=hashlib.sha256()
   with archive.open(name) as stream:
    while block:=stream.read(1048576):h.update(block)
   if h.hexdigest()!=entry['sha256']:raise RuntimeError('Packaged model mismatch; output retained')
 result={'path':str(output),'bytes':output.stat().st_size,'sha256':digest(output),'model_source':meta['source'],'selected_files':meta['selected_files'],'historical_archive_sha256':meta['local_voice_pack']['sha256'],'historical_archive_hash_match':digest(output)==meta['local_voice_pack']['sha256'],'runtime_acceptance':'NOT_RUN','note':'Member hashes are pinned; archive identity may differ from historical packaging. Original model lock unchanged.'}
 receipt.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2));return 0
if __name__=='__main__':raise SystemExit(main())
