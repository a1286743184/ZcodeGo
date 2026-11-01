import base64, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze import decode_stream

RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw")
frames = []
for f in sorted(os.listdir(RAW)):
    if f.endswith(".jsonl"):
        with open(os.path.join(RAW, f), encoding="utf-8") as fh:
            frames.extend(json.loads(l) for l in fh if l.strip())

slots = {}
events = []
for fr in frames:
    if fr.get("k") != "rpc": continue
    f = fr.get("f") or {}
    mi, fi, fc = f.get("messageSeq"), f.get("fragmentIndex"), f.get("fragmentCount")
    if mi is None: continue
    key = "%s|%s|%s" % (f.get("bridgeSessionId"), f.get("bridgeGeneration"), mi)
    slot = slots.setdefault(key, {"n":0,"parts":[None]*fc})
    if slot["parts"][fi] is None:
        try: slot["parts"][fi] = base64.b64decode(f.get("dataBase64") or "")
        except Exception: continue
        slot["n"] += 1
    if slot["n"] < fc: continue
    data = b"".join(p for p in slot["parts"] if p)
    del slots[key]
    try: vals = decode_stream(data)
    except Exception: continue
    if vals and isinstance(vals[0], list) and vals[0] and vals[0][0] == 204:
        arg = vals[1] if len(vals) > 1 else None
        if isinstance(arg, tuple) and arg and arg[0] == "JSON":
            try: events.append(json.loads(arg[1]))
            except Exception: pass

print("事件 JSON 总数:", len(events))
from collections import Counter
topics = Counter(e.get("topic") or (e.get("payload") or {}).get("topic") or "?" for e in events)
print("\n== topic 分布 ==")
for t, c in topics.most_common(15): print("%6d  %s" % (c, t))

def dig(obj, path=""):
    """找含任务数组的结构"""
    if isinstance(obj, dict):
        for k, v in obj.items():
            dig(v, path + "." + k)
    elif isinstance(obj, list) and obj and isinstance(obj[0], dict):
        keys = set()
        for it in obj[:5]: keys.update(it.keys())
        marker = "TASKLIKE" if any(x in keys for x in ("taskId","sessionId","session_id")) else "LIST"
        print("%s %s keys=%s" % (marker, path, sorted(keys)[:18]))

print("\n== tasks-index 快照内部（首个） ==")
for e in events:
    if (e.get("topic") or "") == "controller/tasks-index":
        print(json.dumps(e, ensure_ascii=False)[:3000])
        break

print("\n== sessions-index 快照结构（首个完整） ==")
for e in events:
    t = e.get("topic") or (e.get("payload") or {}).get("topic") or ""
    if t.startswith("sessions-index") or (isinstance(e.get("payload"), dict) and str(e["payload"].get("topic","")).startswith("sessions-index")):
        s = json.dumps(e, ensure_ascii=False)
        print(s[:4000])
        print("...len:", len(s))
        break

print("\n== 事件 arg 顶层键分布 ==")
kc = Counter()
for e in events: kc.update(e.keys())
print(dict(kc))

print("\n== 任务状态值分布（挖 taskId/status 对） ==")
sc = Counter()
def walk(o):
    if isinstance(o, dict):
        if "taskId" in o and "status" in o: sc[str(o["status"])] += 1
        for v in o.values(): walk(v)
    elif isinstance(o, list):
        for v in o: walk(v)
for e in events: walk(e)
print(dict(sc))
