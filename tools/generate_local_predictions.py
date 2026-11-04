#!/usr/bin/env python3
"""Generate a shared C++ seed corpus from original, versioned TSV. Offline only."""
from pathlib import Path
import json
r=Path(__file__).resolve().parents[1]
rows=[]
for line in (r/'data/local-predictions.tsv').read_text(encoding='utf-8').splitlines():
 if not line or line.startswith('#'):continue
 lang,text=line.split('\t');assert lang in ('zh','en','mixed')
 units=text.split('|');assert 1<len(units)<=32 and all(units)
 assert len(''.join(units))<=128
 rows.append(' {'+str({'zh':0,'en':1,'mixed':2}[lang])+', {'+', '.join('u'+json.dumps(x,ensure_ascii=False) for x in units)+'}},')
with (r/'core/local_prediction_data.inc').open('w', encoding='utf-8', newline='\n') as output:
 output.write('// Generated from data/local-predictions.tsv by tools/generate_local_predictions.py. CC0-1.0.\n'+"\n".join(rows)+'\n')
