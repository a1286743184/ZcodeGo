"""测试前静音本机，测试后恢复原音量。

为什么需要它：跑单测、装机、起模拟器、自动化点击时会触发提示音、通知音、
播放器音效，夜间尤其打扰。默认音频设备是系统级总音量，静音它就覆盖所有应用。

用法:
  python tools/silence.py on        # 记录当前状态并静音
  python tools/silence.py off       # 按记录恢复
  python tools/silence.py status    # 打印当前静音/音量

状态写在 %TEMP%\\zcode_silence_state.json，进程被杀也不丢原音量。
"""
import ctypes
import json
import os
import sys
import tempfile

from comtypes import CLSCTX_ALL
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

STATE_PATH = os.path.join(tempfile.gettempdir(), "zcode_silence_state.json")


def _volume():
    device = AudioUtilities.GetSpeakers()
    # 新版 pycaw 暴露 EndpointVolume；旧版要自己 Activate + QueryInterface
    if hasattr(device, "EndpointVolume"):
        return device.EndpointVolume
    interface = device.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return interface.QueryInterface(IAudioEndpointVolume)


def _status(vol):
    return {
        "mute": bool(vol.GetMute()),
        "volume": round(float(vol.GetMasterVolumeLevelScalar()), 4),
    }


def main(argv):
    action = argv[1] if len(argv) > 1 else "status"
    vol = _volume()

    if action == "on":
        state = _status(vol)
        with open(STATE_PATH, "w", encoding="utf-8") as f:
            json.dump(state, f)
        vol.SetMute(1, None)
        print(f"已静音（原状态: mute={state['mute']} volume={state['volume']}）")
        return 0

    if action == "off":
        if not os.path.exists(STATE_PATH):
            vol.SetMute(0, None)
            print("无记录状态，已取消静音（音量保持不变）")
            return 0
        with open(STATE_PATH, "r", encoding="utf-8") as f:
            state = json.load(f)
        vol.SetMasterVolumeLevelScalar(float(state["volume"]), None)
        vol.SetMute(1 if state["mute"] else 0, None)
        os.remove(STATE_PATH)
        print(f"已恢复（mute={state['mute']} volume={state['volume']}）")
        return 0

    if action == "status":
        state = _status(vol)
        print(f"mute={state['mute']} volume={state['volume']}")
        return 0

    print(__doc__)
    return 2


if ctypes.sizeof(ctypes.c_void_p) != 8:
    print("需要 64 位 Python", file=sys.stderr)
    sys.exit(2)

if __name__ == "__main__":
    sys.exit(main(sys.argv))
