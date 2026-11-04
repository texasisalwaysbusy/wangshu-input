from pathlib import Path
p=Path('projects/input-method/android/app/src/main/java/org/wangshu/ime/WangshuIme.java')
s=p.read_text()
checks={'explicit accessible segment key':'setContentDescription("拼音分词")' in s,'refresh updates mode':'updateSegmentKey();' in s,'split action checks session and state':'segmentKeyAllowed(' in s,'three-state shift preserved':'caseMode=(caseMode+1)%3' in s}
for name,passed in checks.items(): print(('PASS ' if passed else 'FAIL ')+name)
if not all(checks.values()): raise SystemExit(1)
print('PASS source wiring only; no Android execution')
