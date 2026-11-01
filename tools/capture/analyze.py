# G2 捕获帧分析器：复刻 Kotlin 协议层逻辑，解码 rpc-frame → Channel 消息，
# 统计真实服务/方法分布与任务事件形态，为 EventNormalizer 固化映射提供证据。
import base64, json, os, sys, zlib

def read_varint(b, pos):
    r = 0; shift = 0; n = 0
    while True:
        if pos >= len(b) or n >= 5: raise ValueError("varint")
        byte = b[pos]; pos += 1; n += 1
        r |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return r, pos
        shift += 7

def decode_value(b, pos, depth=0):
    if depth > 32: raise ValueError("depth")
    tag = b[pos]; pos += 1
    if tag == 0: return None, pos
    if tag == 6:
        v, pos = read_varint(b, pos); return v, pos
    if tag in (1, 2, 3, 5):
        ln, pos = read_varint(b, pos)
        raw = b[pos:pos+ln]; pos += ln
        if len(raw) < ln: raise ValueError("trunc")
        if tag == 1: return raw.decode("utf-8", "replace"), pos
        if tag == 5: return ("JSON", raw.decode("utf-8", "replace")), pos
        return bytes(raw), pos
    if tag == 4:
        cnt, pos = read_varint(b, pos)
        out = []
        for _ in range(cnt):
            v, pos = decode_value(b, pos, depth+1)
            out.append(v)
        return out, pos
    raise ValueError("tag %d" % tag)

def decode_stream(b):
    vals = []; pos = 0
    while pos < len(b):
        v, pos = decode_value(b, pos)
        vals.append(v)
    return vals

def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw")
    frames = []
    if os.path.isdir(src):
        for f in sorted(os.listdir(src)):
            if f.endswith(".jsonl"):
                with open(os.path.join(src, f), encoding="utf-8") as fh:
                    for line in fh:
                        try: frames.append(json.loads(line))
                        except Exception: pass
    print("捕获条目:", len(frames))
    slots = {}
    listen_calls = {}
    listener_map = {}
    call_map = {}
    stats = {}
    samples = {}
    envelope_stats = {}

    def note(key, sample=None):
        stats[key] = stats.get(key, 0) + 1
        if sample is not None and key not in samples:
            samples[key] = sample

    for fr in frames:
        d = fr.get("d"); k = fr.get("k")
        if k == "env":
            o = fr.get("o") or {}
            zt = ((o.get("payload") or {}).get("zcode_type")) or o.get("type")
            note("ENV:%s" % zt, json.dumps(o, ensure_ascii=False)[:400])
            continue
        if k != "rpc": continue
        f = fr.get("f") or {}
        mi = f.get("messageSeq"); fi = f.get("fragmentIndex"); fc = f.get("fragmentCount")
        if mi is None or fi is None or fc is None: continue
        key = "%s|%s|%s" % (f.get("bridgeSessionId"), f.get("bridgeGeneration"), mi)
        slot = slots.setdefault(key, {"n": 0, "parts": [None]*fc, "mb": f.get("messageBytes"), "crc": (f.get("checksum") or {}).get("value")})
        if slot["parts"][fi] is None:
            try: slot["parts"][fi] = base64.b64decode(f.get("dataBase64") or "")
            except Exception: continue
            slot["n"] += 1
        if slot["n"] < fc: continue
        data = b"".join(p for p in slot["parts"] if p is not None)
        del slots[key]
        if slot["mb"] is not None and len(data) != slot["mb"]:
            note("ASSEMBLE:len_mismatch"); continue
        if slot["crc"]:
            hexv = format(zlib.crc32(data) & 0xffffffff, "08x")
            if hexv != slot["crc"].lower():
                note("ASSEMBLE:crc_mismatch"); continue
        try:
            vals = decode_stream(data)
        except Exception as e:
            note("ASSEMBLE:decode_fail:%s" % e); continue
        if not vals or not isinstance(vals[0], list) or not vals[0]:
            note("ASSEMBLE:no_head"); continue
        head = vals[0]; typ = head[0] if isinstance(head[0], int) else None
        rest = vals[1:]
        if typ == 102 and len(head) >= 4:
            cid, svc, mth = head[1], head[2], head[3]
            arg = rest[0] if rest else None
            listen_calls[cid] = (svc, mth)
            note("SEND:listen:%s/%s" % (svc, mth), safe(arg))
        elif typ == 100 and len(head) >= 4:
            cid, svc, mth = head[1], head[2], head[3]
            call_map[cid] = (svc, mth)
            note("SEND:call:%s/%s" % (svc, mth), safe(rest[0] if rest else None))
        elif typ == 201 and len(head) >= 2:
            cid = head[1]; result = rest[0] if rest else None
            tgt = listen_calls.pop(cid, None)
            if tgt and isinstance(result, int):
                listener_map[result] = tgt
                note("RECV:listen-ok:%s/%s→L%s" % (tgt[0], tgt[1], result))
            else:
                c = call_map.pop(cid, None)
                name = "RECV:call-ok:%s/%s" % c if c else "RECV:call-ok:?"
                note(name, safe(result))
        elif typ == 204 and len(head) >= 2:
            lid = head[1]; arg = rest[0] if rest else None
            tgt = listener_map.get(lid)
            name = "RECV:event:%s/%s" % tgt if tgt else "RECV:event:?L%s" % lid
            note(name, safe(arg))
        elif typ == 200:
            note("RECV:initialize", safe(rest)[:200])
        elif typ in (202, 203):
            note("RECV:error%d" % typ, safe(rest)[:200])
        else:
            note("RECV:unknown:%s" % typ, safe(vals)[:200])

    print("\n=== 统计 ===")
    for k in sorted(stats, key=lambda x: -stats[x]):
        print("%6d  %s" % (stats[k], k))
    print("\n=== 样本（每类首例，截断） ===")
    for k in sorted(samples):
        print("\n--- %s" % k)
        print(samples[k][:600])

def safe(v):
    try:
        if isinstance(v, tuple) and v and v[0] == "JSON":
            s = v[1]
            try: return json.dumps(json.loads(s), ensure_ascii=False)[:600]
            except Exception: return s[:600]
        return json.dumps(v, ensure_ascii=False, default=lambda o: "<bytes:%d>" % len(o))[:600]
    except Exception:
        return str(v)[:300]

if __name__ == "__main__":
    main()
