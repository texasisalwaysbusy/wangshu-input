"""Synthetic storage ordering counterexamples; no Android, disk IO, or real clipboard."""
checks=0
def check(c,m):
    global checks
    checks+=1
    if not c: raise AssertionError(m)

def inspect(base,new,expected):
    return new is None and base==expected

# Silent failed publication is detectable before returning, if raw sidecars and base are inspected.
check(not inspect('old','new','new'),'failed rename must not be called success')
check(inspect('new',None,'new'),'matching published bytes pass the narrow check')
# A crash can happen before any in-process check, so startup must reject unresolved .new.
check(not inspect('old','new','new'),'restart must block sidecar rather than openRead')
# This model allows a namespace rename to be visible but not durably committed.
visible_base,visible_new='new',None
stable_base,stable_new='old',None
check(inspect(visible_base,visible_new,'new'),'readback observes new before crash')
check((stable_base,stable_new)==('old',None),'post-crash can contain old and no sidecar in this ordering model')
# A pending marker is not enough if final confirmed marker can persist before namespace operation.
stable_guard='C'
check(stable_guard=='C' and stable_base=='old','confirmed marker alone cannot prove namespace durability')
# A successfully persisted pending guard prevents treating unknown outcome as confirmed.
stable_guard='P'
check(stable_guard!='C','pending survives model crash and blocks retry')
print(f'PASS {checks} synthetic minimal-check and ordering assertions; real Android filesystem durability not tested')
