#!/usr/bin/env python3
"""Extract actual test wait/drain helpers; deterministic real-JDK executor schedule.
No production edits, device, network, installation or cleanup/deletion.
Usage: drain_race.py PROJECT NEW_OUTPUT_DIRECTORY
"""
from pathlib import Path
import subprocess,sys,hashlib,json
project,out=map(lambda x:Path(x).resolve(),sys.argv[1:3]);out.mkdir(parents=True,exist_ok=False)
here=Path(__file__).resolve().parent; source=project/'android/host-tests/association-cancel-20261101/AssociationCancellationChecks.java';text=source.read_text()
def extract(prefix):
 start=text.index(prefix);brace=text.index('{',start);end=brace+1;depth=1
 while depth:depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
await_method=extract('static void await(');drained=extract('void drained()');repaired='barrier' in drained
body=(here/'DrainRaceChecks.java.in').read_text().replace('/* ACTUAL_AWAIT */',await_method).replace('/* ACTUAL_DRAINED */',drained)
java=out/'DrainRaceChecks.java';java.write_text(body);classes=out/'classes';classes.mkdir()
cmd=['java','-XX:-UsePerfData','com.sun.tools.javac.Main','-proc:none','-d',str(classes),str(java)];p=subprocess.run(cmd,capture_output=True,text=True);(out/'compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
cmd=['java','-XX:-UsePerfData','-cp',str(classes),'org.wangshu.ime.DrainRaceChecks',str(repaired).lower()];p=subprocess.run(cmd,capture_output=True,text=True,timeout=15);(out/'run.log').write_text(p.stdout+p.stderr);print(p.stdout+p.stderr,end='')
(out/'RESULT.json').write_text(json.dumps({'scope':'Real JDK executor, controlled task dequeue timing, extracted test helpers. Not Android runtime evidence.','source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'repaired_helper':repaired,'exit':p.returncode},indent=2)+'\n');sys.exit(p.returncode)
