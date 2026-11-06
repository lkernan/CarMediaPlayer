"""Extracts one partition from an Android A/B OTA payload.bin (full OTA only).

    python3 payload_extract.py payload.bin system system.img

(An unknown partition name lists the ones there are.)

Minimal hand-written protobuf reader for update_metadata.proto:
DeltaArchiveManifest{3: block_size, 13: repeated PartitionUpdate}
PartitionUpdate{1: partition_name, 7: new_partition_info{1: size}, 8: repeated InstallOperation}
InstallOperation{1: type, 2: data_offset, 3: data_length, 6: repeated Extent dst_extents}
Extent{1: start_block, 2: num_blocks}
"""
import bz2
import lzma
import struct
import sys


def varint(buf, i):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def fields(buf):
    """Yields (field_number, wire_type, value) for a protobuf message."""
    i = 0
    n = len(buf)
    while i < n:
        key, i = varint(buf, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = varint(buf, i)
        elif wt == 1:
            v = struct.unpack_from("<Q", buf, i)[0]
            i += 8
        elif wt == 2:
            ln, i = varint(buf, i)
            v = buf[i:i + ln]
            i += ln
        elif wt == 5:
            v = struct.unpack_from("<I", buf, i)[0]
            i += 4
        else:
            raise ValueError(f"wire type {wt}")
        yield fn, wt, v


def main(payload_path, want, out_path):
    with open(payload_path, "rb") as f:
        head = f.read(24)
        assert head[:4] == b"CrAU", "not a payload"
        version = struct.unpack(">Q", head[4:12])[0]
        manifest_size = struct.unpack(">Q", head[12:20])[0]
        sig_size = struct.unpack(">I", head[20:24])[0] if version >= 2 else 0
        header_len = 24 if version >= 2 else 20
        f.seek(header_len)
        manifest = f.read(manifest_size)
        data_start = header_len + manifest_size + sig_size

        block_size = 4096
        parts = []
        for fn, wt, v in fields(manifest):
            if fn == 3:
                block_size = v
            elif fn == 13:
                parts.append(v)
        names = []
        for p in parts:
            name, ops, size = None, [], None
            for fn, wt, v in fields(p):
                if fn == 1:
                    name = v.decode()
                elif fn == 7:
                    for f2, _, v2 in fields(v):
                        if f2 == 1:
                            size = v2
                elif fn == 8:
                    ops.append(v)
            names.append(name)
            if name != want:
                continue
            print(f"{name}: {len(ops)} operations, {size} bytes, block {block_size}")
            with open(out_path, "wb") as out:
                if size:
                    out.truncate(size)
                for op in ops:
                    typ, off, ln, dst = 0, 0, 0, []
                    for fn, wt, v in fields(op):
                        if fn == 1:
                            typ = v
                        elif fn == 2:
                            off = v
                        elif fn == 3:
                            ln = v
                        elif fn == 6:
                            e = {1: 0, 2: 0}
                            for f2, _, v2 in fields(v):
                                e[f2] = v2
                            dst.append((e[1], e[2]))
                    if typ == 6 or typ == 7:  # ZERO / DISCARD: already zero
                        continue
                    f.seek(data_start + off)
                    data = f.read(ln)
                    if typ == 0:
                        pass
                    elif typ == 1:
                        data = bz2.decompress(data)
                    elif typ == 8:
                        data = lzma.decompress(data)
                    else:
                        raise ValueError(f"operation type {typ} needs a source image")
                    pos = 0
                    for start, count in dst:
                        out.seek(start * block_size)
                        chunk = data[pos:pos + count * block_size]
                        out.write(chunk)
                        pos += count * block_size
            print("done")
            return
        print("partitions:", names)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
