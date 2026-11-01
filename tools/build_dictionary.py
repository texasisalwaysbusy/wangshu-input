"""Derive a shared toneless pinyin index from the pinned, attributed CC-CEDICT source."""
from pathlib import Path
import re
import hashlib
import json
import math

root=Path(__file__).resolve().parents[1]
repo=root.parents[1]
source=repo/'.tools/cedict-source/cedict_ts.u8'
if not source.is_file():raise SystemExit('Restore the verified Debian CC-CEDICT source first; see docs/dictionary-source.json')
words={}
frequency_source=repo/'.tools/jieba-source/dict.txt'
frequency_info=json.loads((root/'docs/frequency-source.json').read_text())
if not frequency_source.is_file() or hashlib.sha256(frequency_source.read_bytes()).hexdigest()!=frequency_info['dictionary_sha256']:
    raise SystemExit('Restore pinned jieba dictionary frequencies; see docs/frequency-source.json')
frequencies={}
for line in frequency_source.read_text().splitlines():
    fields=line.split()
    if len(fields)>=2:
        try:frequencies[fields[0]]=int(fields[1])
        except ValueError:pass
for line in source.read_text().splitlines():
    if not line or line.startswith('#'):continue
    match=re.match(r'^\S+ (\S+) \[([^]]+)\]',line)
    if not match:continue
    text,pinyin=match.groups()
    if not any(ord(c)>=0x3400 for c in text):continue
    key=pinyin.lower().replace('u:','v').replace('ü','v')
    key=re.sub(r'[0-5\s\-\']','',key)
    if not re.fullmatch('[a-z]{1,80}',key) or len(text)>60:continue
    words[(key,text)]=1000+round(math.log1p(frequencies.get(text,0))*100000)+min(20,len(text))*20

# Hand-authored common defaults. Dataset order alone is not a frequency model.
common={
 'de':'的','shi':'是','wo':'我','ni':'你','ta':'他','men':'们','zai':'在','bu':'不','le':'了','hao':'好','ma':'吗',
 'yi':'一','er':'二','san':'三','si':'四','wu':'五','liu':'六','qi':'七','ba':'八','jiu':'九','ling':'零',
 'zhong':'中','wen':'文','shu':'数','ru':'入','fa':'法','xue':'学','gong':'工','zuo':'作','yao':'要','you':'有','he':'和','ke':'可','yiqi':'一起',
 'nihao':'你好','zhongwen':'中文','shurufa':'输入法','gongshi':'公式','shuxue':'数学','fuhao':'符号','yuyin':'语音',
 'jifen':'积分','hanshu':'函数','daoshu':'导数','xiangliang':'向量','juzhen':'矩阵','xiexie':'谢谢','woshi':'我是','women':'我们',
 'jintian':'今天','xianzai':'现在','shuru':'输入','shuchu':'输出','diannao':'电脑','shouji':'手机','wangshu':'望舒','tongbu':'同步'
}
for key,text in common.items():words[(key,text)]=2000000 if len(text)>1 else 1500000
out=root/'data/pinyin.tsv'
header='# Derived from CC-CEDICT, MDBG, 2025-03-29. CC BY-SA 4.0.\n# Changes: simplified headwords, toneless pinyin; jieba MIT frequency ranking plus curated defaults. See CC-CEDICT-NOTICE.txt.\n'
out.write_text(header+''.join(f'{key}\t{text}\t{score}\n' for (key,text),score in sorted(words.items())))
notice='''CC-CEDICT — community-maintained Chinese-English dictionary, published by MDBG.
Source release: 2025-03-29 (Debian source repack20250329).
Original referenced work: CEDICT, Copyright (C) 1997, 1998 Paul Andrew Denisowski.
License: Creative Commons Attribution-ShareAlike 4.0 International.
https://creativecommons.org/licenses/by-sa/4.0/
https://www.mdbg.net/chinese/dictionary?page=cc-cedict
This pinyin.tsv is an adapted dataset: simplified headwords and normalized toneless
pinyin were extracted; English definitions/traditional spellings were omitted;
MIT-licensed jieba 0.42.1 word frequencies and hand-authored common-word rankings were
added. Jieba copyright (c) 2013 Sun Junyi; license in notices/JIEBA-LICENSE.txt.
The adapted dataset remains CC BY-SA 4.0.
Source URLs, pinned SHA-256 values and the transformation script accompany the project.
No MDBG endorsement is implied. Software code and hand-authored symbol mappings are separate.
'''
(root/'data/CC-CEDICT-NOTICE.txt').write_text(notice)
meta={'entries':len(words),'keys':len({key for key,text in words}),'pinyin_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'symbols_sha256':hashlib.sha256((root/'data/symbols.tsv').read_bytes()).hexdigest(),'upstream_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'frequency_sha256':frequency_info['dictionary_sha256'],'license':'CC-BY-SA-4.0 for derived dictionary; jieba MIT frequencies; symbols hand-authored'}
(root/'data/version.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(meta,indent=2))
