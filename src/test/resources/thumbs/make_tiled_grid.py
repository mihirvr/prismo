# Made the test file tiled-grid.avif (1.0.1): four one-colour 64x48 AV1 tiles joined into one 2x2 tile grid
# (128x96), the layout phone HEICs use. Tiles first, by the bundled ffmpeg:
#   for c in red lime blue yellow; do ffmpeg -f lavfi -i color=$c:s=64x48 -frames:v 1 -c:v libaom-av1 \
#       -still-picture 1 -pix_fmt yuv420p tile_$c.avif; done
# then: python make_tiled_grid.py tiled-grid.avif tile_red.avif tile_lime.avif tile_blue.avif tile_yellow.avif
import struct, sys
def box(t, payload): return struct.pack('>I4s', 8 + len(payload), t.encode()) + payload
def full(t, v, f, payload): return box(t, struct.pack('>I', (v << 24) | f) + payload)
def children(b, o, end):
    out = {}
    while o < end:
        sz, t = struct.unpack('>I4s', b[o:o+8]); out.setdefault(t.decode(), []).append((o, sz)); o += sz
    return out
def parse(path):
    b = open(path, 'rb').read(); top = children(b, 0, len(b))
    mo, ms = top['meta'][0]; meta = children(b, mo + 12, mo + ms)
    po, ps = meta['iprp'][0]; ipco_o, ipco_s = children(b, po + 8, po + ps)['ipco'][0]
    props = {t: b[o:o+s] for t, [(o, s)] in children(b, ipco_o + 8, ipco_o + ipco_s).items()}
    do, ds = top['mdat'][0]
    return props, b[do+8:do+ds]
tiles = [parse(p) for p in sys.argv[2:6]]
props = tiles[0][0]
datas = [t[1] for t in tiles]
grid = struct.pack('>BBBBHH', 0, 0, 1, 1, 128, 96)  # version, flags, rows-1, columns-1, width, height
ftyp = box('ftyp', b'avif' + struct.pack('>I', 0) + b'avifmif1miaf')
hdlr = full('hdlr', 0, 0, struct.pack('>I', 0) + b'pict' + b'\0' * 12 + b'\0')
pitm = full('pitm', 0, 0, struct.pack('>H', 5))
infes = b''.join(full('infe', 2, 0, struct.pack('>HH', i, 0) + (b'av01' if i < 5 else b'grid') + b'\0')
                 for i in range(1, 6))
iinf = full('iinf', 0, 0, struct.pack('>H', 5) + infes)
dimg = box('dimg', struct.pack('>HH', 5, 4) + struct.pack('>4H', 1, 2, 3, 4))
iref = full('iref', 0, 0, dimg)
ispe_tile = full('ispe', 0, 0, struct.pack('>II', 64, 48))
ispe_grid = full('ispe', 0, 0, struct.pack('>II', 128, 96))
ipco = box('ipco', props['av1C'] + ispe_tile + ispe_grid + props['pixi'])  # 1 av1C, 2 ispe tile, 3 ispe grid, 4 pixi
assoc_tile = lambda i: struct.pack('>HB', i, 3) + bytes([0x80 | 1, 2, 4])
ipma = full('ipma', 0, 0, struct.pack('>I', 5) + b''.join(assoc_tile(i) for i in range(1, 5))
            + struct.pack('>HB', 5, 1) + bytes([3]))
iprp = box('iprp', ipco + ipma)
def build(offsets):
    items = [(i + 1, offsets[i], len(d)) for i, d in enumerate(datas + [grid])]
    iloc = full('iloc', 0, 0, bytes([0x44, 0x00]) + struct.pack('>H', 5)
                + b''.join(struct.pack('>HHHII', i, 0, 1, o, n) for i, o, n in items))
    meta = full('meta', 0, 0, hdlr + pitm + iloc + iinf + iref + iprp)
    return ftyp + meta
head = build([0] * 5)
start = len(head) + 8
offsets, o = [], start
for d in datas + [grid]:
    offsets.append(o); o += len(d)
out = build(offsets) + box('mdat', b''.join(datas) + grid)
open(sys.argv[1], 'wb').write(out)
print(len(out), 'bytes')
