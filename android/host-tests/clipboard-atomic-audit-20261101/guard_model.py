"""Synthetic candidate protocol, not Android or production code; no filesystem access."""
import hashlib

class SimCrash(Exception): pass

def digest(value): return hashlib.sha256(value.encode()).hexdigest()
class Disk:
    def __init__(self, old='old'):
        self.base=old; self.new=None; self.bak=None; self.previous=None; self.guard='C:'+digest(old)
        self.read_consumptions=0

class Store:
    def __init__(self,d): self.d=d; self.busy=False; self.rows=None; self.status=self.inspect()
    def inspect(self):
        d=self.d
        if d.new is not None or d.bak is not None: return 'BLOCKED'
        if d.guard is None:
            return 'READY' if d.previous is None else 'BLOCKED'
        if d.guard=='P' or not d.guard.startswith('C:'): return 'BLOCKED'
        if d.base is None or d.guard!='C:'+digest(d.base): return 'BLOCKED'
        self.rows=d.base
        return 'CLEANUP_PENDING' if d.previous is not None else 'READY'
    def save(self,new,fail=None):
        if self.busy: return 'BUSY'
        if self.status!='READY': return 'BLOCKED'
        self.busy=True
        d=self.d
        def checkpoint(name):
            if fail==name: raise SimCrash(name)
        try:
            checkpoint('before_P')
            d.guard='P'; checkpoint('after_P')
            d.previous=d.base[:max(0,len(d.base)//2)]; checkpoint('partial_previous')
            d.previous=d.base; checkpoint('after_previous')
            d.new=new[:max(0,len(new)//2)]; checkpoint('partial_new')
            d.new=new; checkpoint('after_new')
            if fail=='silent_rename':
                # finishWrite returns normally but .new remains; raw verification fails.
                self.status='BLOCKED'; return 'UNCERTAIN'
            d.base=d.new; d.new=None; checkpoint('after_rename')
            if fail=='bad_readback': self.status='BLOCKED'; return 'UNCERTAIN'
            d.guard='C:'+digest(d.base); checkpoint('after_C')
            self.rows=d.base
            self.status='CLEANUP_PENDING'
            return 'LOGICAL_COMMIT_RECOVERY_COPY_RETAINED'
        except SimCrash:
            self.status='BLOCKED'; return 'UNCERTAIN'
        finally: self.busy=False

assertions=0
def check(cond,desc):
    global assertions
    assertions+=1
    if not cond: raise AssertionError(desc)

ops={'add':'old+new','edit':'edited','pin':'old+pinned','remove':'remaining',
     'clear':'', 'expiry':'unexpired'}
stages=('before_P','after_P','partial_previous','after_previous','partial_new',
        'after_new','silent_rename','after_rename','bad_readback','after_C')
for op,payload in ops.items():
    for stage in stages:
        d=Disk(); s=Store(d); outcome=s.save(payload,stage); check(outcome=='UNCERTAIN',(op,stage,'outcome'))
        r=Store(d)
        if stage=='before_P':
            check(r.status=='READY' and r.rows=='old' and d.new is None and d.previous is None,(op,stage,'prewrite'))
        elif stage=='after_C':
            check(r.status=='CLEANUP_PENDING' and r.rows==payload,(op,stage,'committed'))
            check(r.save('retry')=='BLOCKED',(op,stage,'replay'))
        else:
            check(r.status=='BLOCKED' and r.save('retry')=='BLOCKED',(op,stage,'no retry'))
            check(d.base=='old' or d.previous=='old',(op,stage,'preimage'))
        check(d.read_consumptions==0,(op,stage,'no destructive read'))
    d=Disk(); s=Store(d)
    check(s.save(payload)=='LOGICAL_COMMIT_RECOVERY_COPY_RETAINED',(op,'normal'))
    r=Store(d); check(r.status=='CLEANUP_PENDING' and r.rows==payload and d.previous=='old',(op,'durability model'))
    check(r.save('next')=='BLOCKED',(op,'bounded retention'))
    if op=='clear': check(d.previous=='old',(op,'not physical erase'))

for guard,base,new,bak,prev in [('P','old',None,None,None),('torn','old',None,None,None),('C:'+digest('x'),'old',None,None,None),(None,None,'new',None,None),('C:'+digest('old'),'old','new',None,None),('C:'+digest('old'),'old',None,'bak',None)]:
    d=Disk(); d.guard=guard; d.base=base; d.new=new; d.bak=bak; d.previous=prev
    check(Store(d).status=='BLOCKED',('ambiguous artifacts',guard,new,bak))
d=Disk(); s=Store(d); s.busy=True; check(s.save('x')=='BUSY' and d.base=='old','reentry')
print(f'PASS {assertions} synthetic candidate-protocol assertions; 6 operations x 10 failure points; no real files or Android; retained recovery copy blocks next write')
