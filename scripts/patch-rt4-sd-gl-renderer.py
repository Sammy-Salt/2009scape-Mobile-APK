#!/usr/bin/env python3
"""Patch the bundled RT4 client so Play SD does not suppress OpenGL startup."""
import argparse
import struct
import tempfile
import warnings
import zipfile
from pathlib import Path

TARGET = "rt4/DisplayMode.class"
METHOD = "setWindowMode"
DESCRIPTOR = "(ZIZIII)V"
# iload 6; ifne 500 — launchSD=true currently bypasses GlRenderer.init().
BLOCKER = bytes((0x15, 0x06, 0x9A, 0x00, 0x10))
REPLACEMENT = b"\x00" * len(BLOCKER)

def u2(data, pos):
    return struct.unpack_from(">H", data, pos)[0]

def u4(data, pos):
    return struct.unpack_from(">I", data, pos)[0]

def method_code(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("DisplayMode.class is not a valid JVM class")
    count, cp, pos, i = u2(data, 8), {}, 10, 1
    while i < count:
        tag, pos = data[pos], pos + 1
        if tag == 1:
            n = u2(data, pos); pos += 2
            cp[i] = data[pos:pos+n].decode("utf-8", "replace"); pos += n
        elif tag in (3, 4): pos += 4
        elif tag in (5, 6): pos += 8; i += 1
        elif tag in (7, 8, 16, 19, 20): pos += 2
        elif tag in (9, 10, 11, 12, 17, 18): pos += 4
        elif tag == 15: pos += 3
        else: raise ValueError(f"Unknown constant-pool tag {tag}")
        i += 1
    pos += 6
    interfaces = u2(data, pos); pos += 2 + 2 * interfaces
    def skip_members(p):
        n = u2(data, p); p += 2
        for _ in range(n):
            p += 6
            attrs = u2(data, p); p += 2
            for _ in range(attrs):
                p += 6 + u4(data, p + 2)
        return p
    pos = skip_members(pos)
    methods, pos, found = u2(data, pos), pos + 2, []
    for _ in range(methods):
        name, desc = cp.get(u2(data, pos + 2)), cp.get(u2(data, pos + 4))
        attrs, pos = u2(data, pos + 6), pos + 8
        for _ in range(attrs):
            attr_name, length = cp.get(u2(data, pos)), u4(data, pos + 2)
            body = pos + 6
            if name == METHOD and desc == DESCRIPTOR and attr_name == "Code":
                code_len = u4(data, body + 4)
                found.append((body + 8, code_len))
            pos = body + length
    if len(found) != 1:
        raise ValueError(f"Expected one {METHOD}{DESCRIPTOR} method; found {len(found)}")
    return found[0]

def transform(data, check=False):
    start, length = method_code(data)
    code = data[start:start+length]
    matches = [i for i in range(len(code)) if code.startswith(BLOCKER, i)]
    marker = REPLACEMENT + bytes((0xB2,))
    if check:
        if matches or code.count(marker) != 1:
            raise ValueError("RT4 Play-SD OpenGL-init patch is missing or ambiguous")
        return data
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one launchSD GL-init guard; found {len(matches)}")
    at, out = matches[0], bytearray(data)
    out[start+at:start+at+len(BLOCKER)] = REPLACEMENT
    return bytes(out)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    with zipfile.ZipFile(args.jar, "r") as src:
        entries = [(info, src.read(info)) for info in src.infolist()]
    output, found = [], False
    for info, payload in entries:
        if info.filename == TARGET:
            payload = transform(payload, args.check)
            found = True
        output.append((info, payload))
    if not found:
        raise SystemExit(f"Missing required class: {TARGET}")
    if args.check:
        print("RT4 Play-SD OpenGL-init patch verified.")
        return
    with tempfile.NamedTemporaryFile(dir=args.jar.parent, suffix=".jar", delete=False) as tmp:
        temp = Path(tmp.name)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(temp, "w") as dst:
                for info, payload in output:
                    dst.writestr(info, payload)
        with zipfile.ZipFile(temp, "r") as check:
            bad = check.testzip()
            if bad: raise ValueError(f"Patched JAR failed ZIP validation: {bad}")
            transform(check.read(TARGET), check=True)
        temp.replace(args.jar)
    finally:
        if temp.exists(): temp.unlink()
    print("Patched RT4: Play SD can now attempt the existing OpenGL/EGL init path.")

if __name__ == "__main__":
    main()
