"""USB-only music fader. Uses Windows session volume; never changes master or mic volume.

Run with Python on Windows, then adb reverse tcp:8556 tcp:8556.
Only local loopback accepts connections. Protocol: one bounded JSON line per request.
"""
import ctypes as c
from ctypes import wintypes as w
import json
import math
import pathlib
import socketserver
import uuid

MEDIA_APPS = frozenset({"chrome.exe", "msedge.exe", "firefox.exe", "brave.exe",
    "spotify.exe", "vlc.exe", "wmplayer.exe", "music.ui.exe", "applemusic.exe",
    "foobar2000.exe", "mpv.exe", "mpc-hc.exe", "mpc-hc64.exe"})

class GUID(c.Structure):
    _fields_ = [('a', c.c_uint32), ('b', c.c_uint16), ('d', c.c_uint16), ('e', c.c_ubyte * 8)]
    def __init__(self, text):
        super().__init__()
        c.memmove(c.byref(self), uuid.UUID(text).bytes_le, 16)

def call(ptr, slot, types, *args):
    table = c.cast(ptr, c.POINTER(c.POINTER(c.c_void_p))).contents
    hr = c.WINFUNCTYPE(c.c_long, c.c_void_p, *types)(table[slot])(ptr, *args)
    if hr < 0:
        raise OSError(f'COM {slot}: {hr & 0xffffffff:08x}')

def release(ptr):
    if ptr and ptr.value:
        call(ptr, 2, [])

def query(ptr, iid):
    result = c.c_void_p()
    call(ptr, 0, [c.POINTER(GUID), c.POINTER(c.c_void_p)], c.byref(GUID(iid)), c.byref(result))
    return result

def activate(device, iid):
    result = c.c_void_p()
    call(device, 3, [c.POINTER(GUID), w.DWORD, c.c_void_p, c.POINTER(c.c_void_p)],
         c.byref(GUID(iid)), 23, None, c.byref(result))
    return result

ole = c.OleDLL('ole32')
ole.CoCreateInstance.argtypes = [c.POINTER(GUID), c.c_void_p, w.DWORD, c.POINTER(GUID), c.POINTER(c.c_void_p)]
kernel = c.WinDLL('kernel32', use_last_error=True)
kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
kernel.OpenProcess.restype = w.HANDLE
kernel.CloseHandle.argtypes = [w.HANDLE]
kernel.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, c.POINTER(w.DWORD)]

def process_name(pid):
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return ""
    try:
        name = c.create_unicode_buffer(32768)
        size = w.DWORD(len(name))
        return pathlib.Path(name.value).name.lower() if kernel.QueryFullProcessImageNameW(handle, 0, name, c.byref(size)) else ""
    finally:
        kernel.CloseHandle(handle)

def music_sessions(level=None):
    refs, levels, apps = [], [], set()
    def keep(ptr):
        refs.append(ptr)
        return ptr
    try:
        enumerator = keep(c.c_void_p())
        hr = ole.CoCreateInstance(c.byref(GUID('BCDE0395-E52F-467C-8E3D-C4579291692E')), None, 23,
            c.byref(GUID('A95664D2-9614-4F35-A746-DE8DB63617E6')), c.byref(enumerator))
        if hr < 0:
            raise OSError("Cannot enumerate audio devices")
        devices = keep(c.c_void_p())
        call(enumerator, 3, [c.c_int, w.DWORD, c.POINTER(c.c_void_p)], 0, 1, c.byref(devices))
        count = w.UINT()
        call(devices, 3, [c.POINTER(w.UINT)], c.byref(count))
        for index in range(count.value):
            device = keep(c.c_void_p())
            call(devices, 4, [w.UINT, c.POINTER(c.c_void_p)], index, c.byref(device))
            manager = keep(activate(device, '77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F'))
            sessions = keep(c.c_void_p())
            call(manager, 5, [c.POINTER(c.c_void_p)], c.byref(sessions))
            total = c.c_int()
            call(sessions, 3, [c.POINTER(c.c_int)], c.byref(total))
            for j in range(total.value):
                control = keep(c.c_void_p())
                call(sessions, 4, [c.c_int, c.POINTER(c.c_void_p)], j, c.byref(control))
                control2 = keep(query(control, 'bfb7ff88-7239-4fc9-8fa2-07c950be9c6d'))
                pid = w.DWORD()
                call(control2, 14, [c.POINTER(w.DWORD)], c.byref(pid))
                name = process_name(pid.value)
                if name not in MEDIA_APPS:
                    continue
                volume = keep(query(control, '87CE5498-68D6-44E5-9215-6DA47EF883D8'))
                if level is not None:
                    call(volume, 3, [c.c_float, c.c_void_p], level, None)
                actual = c.c_float()
                call(volume, 4, [c.POINTER(c.c_float)], c.byref(actual))
                levels.append(actual.value)
                apps.add(name)
        return {"available": bool(levels), "music": max(levels, default=1.0), "apps": sorted(apps)}
    finally:
        for ptr in reversed(refs):
            release(ptr)

class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(1)
        try:
            line = self.rfile.readline(257)
            if len(line) > 256 or not line.endswith(b'\n'):
                raise ValueError("Invalid request")
            request = json.loads(line)
            level = request.get("music")
            if level is not None and (isinstance(level, bool) or not isinstance(level, (int, float))
                                      or not math.isfinite(level) or not 0 <= level <= 1):
                raise ValueError("Invalid level")
            response = music_sessions(level)
        except Exception:
            response = {"available": False, "music": 1.0}
        self.wfile.write(json.dumps(response).encode() + b'\n')

class Server(socketserver.TCPServer):
    allow_reuse_address = True

if __name__ == '__main__':
    ole.CoInitializeEx(None, 0)
    try:
        with Server(('127.0.0.1', 8556), Handler) as server:
            print('Music fader ready on USB loopback port 8556', flush=True)
            server.serve_forever()
    finally:
        ole.CoUninitialize()
