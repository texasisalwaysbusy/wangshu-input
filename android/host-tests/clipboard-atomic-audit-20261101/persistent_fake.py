"""Synthetic JSON backing only. Two separate Python processes model one publish failure and one restart."""
import json, pathlib, sys
p=pathlib.Path(__file__).with_name('synthetic-once.json')
if sys.argv[1]=='publish':
    # Exclusive initial creation: never overwrite earlier evidence.
    state={'base':'SYNTHETIC_OLD','new':'SYNTHETIC_CLEAR','ram':'SYNTHETIC_CLEAR','publish_result':'RETURNED_SUCCESS'}
    with p.open('x',encoding='utf-8') as f: json.dump(state,f); f.flush()
    assert state['base']!=state['ram'] and state['new']==state['ram']
    print('PUBLISH: synthetic clear reported success; synthetic base still old; fake new retained')
elif sys.argv[1]=='restart':
    # Simulate AOSP openRead behavior on a copy, not real sidecar files or real AtomicFile.
    state=json.loads(p.read_text(encoding='utf-8'))
    assert state=={'base':'SYNTHETIC_OLD','new':'SYNTHETIC_CLEAR','ram':'SYNTHETIC_CLEAR','publish_result':'RETURNED_SUCCESS'}
    loaded=state['base'] if state['base'] and state['new'] else None
    assert loaded=='SYNTHETIC_OLD'
    print('RESTART: simulated AOSP read would discard fake new and load synthetic old; evidence file unchanged')
else: raise SystemExit('unknown mode')
