import base64, json, os, sys, zlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze import decode_stream, safe

RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw")
frames = []
for f in sorted(os.listdir(RAW)):
    if f.endswith(".jsonl"):
        with open(os.path.join(RAW, f), encoding="utf-8") as fh:
            frames.extend(json.loads(l) for l in fh if l.strip())

slots = {}
messages = []  # (dir, decoded values, messageSeq)
for fr in frames:
    if fr.get("k") != "rpc": continue
    f = fr.get("f") or {}
    mi, fi, fc = f.get("messageSeq"), f.get("fragmentIndex"), f.get("fragmentCount")
    if mi is None: continue
    key = "%s|%s|%s" % (f.get("bridgeSessionId"), f.get("bridgeGeneration"), mi)
    slot = slots.setdefault(key, {"n":0,"parts":[None]*fc,"crc":(f.get("checksum") or {}).get("value")})
    if slot["parts"][fi] is None:
        try: slot["parts"][fi] = base64.b64decode(f.get("dataBase64") or "")
        except Exception: continue
        slot["n"] += 1
    if slot["n"] < fc: continue
    data = b"".join(p for p in slot["parts"] if p)
    del slots[key]
    try: vals = decode_stream(data)
    except Exception: continue
    messages.append((fr.get("d"), mi, vals))

print("== 解码消息总数:", len(messages))

# 1) 所有 201 帧
c201 = [m for m in messages if m[2] and isinstance(m[2][0], list) and m[2][0] and m[2][0][0] == 201]
print("\n== 201 帧数:", len(c201))
for d, mi, vals in c201[:25]:
    head = vals[0]; rest = vals[1:]
    print(" dir=%s mi=%s head=%s rest=%s" % (d, mi, json.dumps(head)[:120], safe(rest[0]) if rest else "-"))

# 2) 前几个 event 帧（含 L14）
evs = [m for m in messages if m[2] and isinstance(m[2][0], list) and m[2][0] and m[2][0][0] == 204]
print("\n== 204 帧数:", len(evs))
seen = set()
for d, mi, vals in evs:
    lid = vals[0][1] if len(vals[0]) > 1 else None
    if lid in seen: continue
    seen.add(lid)
    arg = vals[1] if len(vals) > 1 else None
    print(" L%s dir=%s mi=%s arg=%s" % (lid, d, mi, safe(arg)[:500]))
    if len(seen) >= 12: break

# 3) subscribe 相关 call-ok
for name in ("subscribeControllerV4", "subscribeSessionsIndexV4", "listTaskList", "task-groups/read", "getTaskMeta"):
    calls = [m for m in messages if m[2] and isinstance(m[2][0], list) and m[2][0] and m[2][0][0] == 100 and len(m[2][0]) >= 4 and m[2][0][3] == name]
    print("\n== call %s ×%d" % (name, len(calls)))
    for d, mi, vals in calls[:2]:
        print("  send head=%s arg=%s" % (json.dumps(vals[0])[:160], safe(vals[1] if len(vals) > 1 else None)[:300]))
