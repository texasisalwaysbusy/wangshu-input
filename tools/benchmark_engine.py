#!/usr/bin/env python3
"""Compare existing portable core with optimization; host CPU evidence, not phone latency.

No packages, downloads, cache eviction or product file changes. Output paths must
be new; generated executables live in the project's ignored build directory.
"""
import argparse,datetime,hashlib,json,statistics,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Preserve old evidence: choose a new output')
project=Path(__file__).resolve().parents[1];build=project/'build/engine-benchmark';build.mkdir(exist_ok=True,parents=True)
source=build/'probe.cpp';source.write_text(r'''
#include "engine.h"
#include <chrono>
#include <iostream>
#include <sys/resource.h>
int main(int argc,char** argv){if(argc!=2)return 2;wangshu::Engine e;auto begin=std::chrono::steady_clock::now();if(!e.load(argv[1]))return 3;auto loaded=std::chrono::steady_clock::now();std::string output;for(const auto& query:{"nihao","zhidao","shi","gongshi","xuexi","\\alpha","\\X","\\Gamma"}){output+=query;for(auto c:e.lookup(query,9,0))output+="\t"+c.text+":"+std::to_string(c.score);output+="\n";}auto end=std::chrono::steady_clock::now();struct rusage usage{};getrusage(RUSAGE_SELF,&usage);std::cout<<e.entries()<<" "<<std::chrono::duration<double,std::milli>(loaded-begin).count()<<" "<<std::chrono::duration<double,std::milli>(end-loaded).count()<<" "<<usage.ru_maxrss<<"\n"<<output;}
''')
record={'time_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Linux host existing toolchain/portable core only, cached OS filesystem; not Android, phone latency, ASR or accuracy','source_sha256':{str(x.relative_to(project)):hashlib.sha256(x.read_bytes()).hexdigest() for x in [project/'core/engine.cpp',project/'core/engine.h',project/'data/pinyin.tsv',project/'data/symbols.tsv']},'runs':[]}
for option in ['-O0','-O2']:
 binary=build/('probe-'+option[1:]);subprocess.run(['g++','-std=c++17',option,'-I',str(project/'core'),str(source),str(project/'core/engine.cpp'),'-o',str(binary)],check=True,capture_output=True)
 for iteration in range(3):
  run=subprocess.run([str(binary),str(project/'data')],check=True,text=True,capture_output=True,timeout=60);head,body=run.stdout.split('\n',1);entries,load,lookup,rss=head.split();record['runs'].append({'optimization':option,'iteration':iteration+1,'entries':int(entries),'load_ms':float(load),'eight_probe_lookups_ms':float(lookup),'host_peak_rss_kib':int(rss),'candidate_output_sha256':hashlib.sha256(body.encode()).hexdigest()})
assert len({r['candidate_output_sha256'] for r in record['runs']})==1
assert all(r['entries']==119556 for r in record['runs'])
record['median_load_ms']={o:statistics.median(r['load_ms'] for r in record['runs'] if r['optimization']==o) for o in ['-O0','-O2']};record['state']='PASS_SAME_PROBE_OUTPUT';a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(record,indent=2)+'\n');print(record['state'],record['median_load_ms'])
