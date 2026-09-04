#!/usr/bin/env python3
"""读平板的 btsnoop 抓包,把一台 BLE 外设的链路史摆出来。

用法:
    adb bugreport br.zip && python3 -c "import zipfile; zipfile.ZipFile('br.zip').extractall('br')"
    python3 tools/btsnoop.py C5:C5:30:98:47:5C br/FS/data/misc/bluetooth/logs/btsnoop_hci.log.03 \
        br/FS/data/misc/bluetooth/logs/btsnoop_hci.log.02 ... br/FS/data/misc/bluetooth/logs/btsnoop_hci.log

文件按时间从早到晚传(编号大的更早,不带编号的最新)。只依赖标准库。
只认 H4 格式(安卓就是这个),时间戳按本地时间原样显示,安卓写进去的已经是本地时间。

输出三段:
  1. 链路事件:建链、参数更新、断开(附断开前平板最后一次收到对方包距今多久)、
     断开之后第一条来自对方的广播报告。
  2. 收包停顿:连着时两次收到对方 ACL 数据包之间 ≥ 300 ms 的间隔。注意这只看 HCI
     层的数据包,链路层的空包看不到,所以「摇杆没动」和「空口静默」在这里长得一样,
     要结合当时在不在玩来读。
  3. 每次断开前 12 秒,每 500 ms 收到对方多少包,看链路是逐渐变稀还是瞬间归零;
     以及静默期内平板有没有从别的链路收到包(接收机是不是活的)。
"""

import datetime
import struct
import sys
from collections.abc import Iterator

Record = tuple[int, int, bytes]  # (时间戳 us, flags, 包体)
Drop = list  # [最后收包时刻, 断开时刻, 重连时刻或 None, handle]

EPOCH_OFF = 0x00DCDDB30F2F8000  # 公元 0 年到 1970 年之间的微秒数
GAP_MS = 300.0
WINDOW_S = 12.0


def ts(us: int) -> datetime.datetime:
    return datetime.datetime.fromtimestamp(
        (us - EPOCH_OFF) / 1e6, datetime.timezone.utc
    )


def records(path: str) -> Iterator[Record]:
    with open(path, 'rb') as f:
        assert f.read(16)[:8] == b'btsnoop\0', path
        while True:
            h = f.read(24)
            if len(h) < 24:
                return
            _orig, incl, flags, _drops, t = struct.unpack('>IIIIq', h)
            yield t, flags, f.read(incl)


def le_meta(d: bytes) -> tuple[int, bytes] | None:
    """HCI 事件里的 LE Meta 事件 → (子事件码, 参数);不是就返回 None。"""
    if d[0] != 4 or d[1] != 0x3E:
        return None
    p = d[3 : 3 + d[2]]
    return (p[0], p) if p else None


def conn_complete(p: bytes, peer: bytes) -> tuple[int, float] | None:
    """LE (Enhanced) Connection Complete → (handle, interval_ms);不是这台外设的就 None。"""
    sub = p[0]
    if sub == 0x01 and p[6:12] == peer:
        return struct.unpack('<H', p[2:4])[0] & 0x0FFF, struct.unpack(
            '<H', p[12:14]
        )[0] * 1.25
    if sub == 0x0A and p[6:12] == peer:
        return struct.unpack('<H', p[2:4])[0] & 0x0FFF, struct.unpack(
            '<H', p[24:26]
        )[0] * 1.25
    return None


def adv_from(p: bytes, peer: bytes) -> bool:
    return p[0] == 0x02 and p[4:10] == peer or p[0] == 0x0D and p[5:11] == peer


def acl_handle(d: bytes) -> int:
    return struct.unpack('<H', d[1:3])[0] & 0x0FFF


def timeline(pk: list[Record], peer: bytes) -> tuple[list, list, list[Drop]]:
    handle = None
    last_rx = None
    events, gaps, drops = [], [], []
    for t, flags, d in pk:
        if not d:
            continue
        meta = le_meta(d)
        if meta:
            sub, p = meta
            cc = conn_complete(p, peer)
            if cc:
                handle, interval = cc
                last_rx = t
                events.append((
                    t,
                    f'建链 handle=0x{handle:04x} status={p[1]} interval={interval:.2f}ms',
                ))
                if drops and drops[-1][2] is None:
                    drops[-1][2] = t
            elif (
                sub == 0x03
                and handle is not None
                and (struct.unpack('<H', p[2:4])[0] & 0x0FFF) == handle
            ):
                iv, lat, to = struct.unpack('<HHH', p[4:10])
                events.append((
                    t,
                    f'参数更新 status={p[1]} interval={iv * 1.25:.2f}ms latency={lat} timeout={to * 10}ms',
                ))
            elif (
                handle is None
                and adv_from(p, peer)
                and (not events or '广播' not in events[-1][1])
            ):
                events.append((t, '断开后首次收到它的广播'))
        elif d[0] == 4 and d[1] == 0x05 and handle is not None:
            p = d[3 : 3 + d[2]]
            if (struct.unpack('<H', p[1:3])[0] & 0x0FFF) == handle:
                events.append((
                    t,
                    f'断开 reason=0x{p[3]:02x}  最后收到它的包距今 {(t - last_rx) / 1000:.0f} ms',
                ))
                drops.append([last_rx, t, None, handle])
                handle = None
        elif (
            d[0] == 2
            and handle is not None
            and flags & 1
            and acl_handle(d) == handle
        ):
            if (t - last_rx) / 1000 >= GAP_MS:
                gaps.append((last_rx, (t - last_rx) / 1000))
            last_rx = t
    return events, gaps, drops


def drop_windows(pk: list[Record], peer: bytes, drops: list[Drop]) -> None:
    for last_rx, dc, rc, handle in drops:
        lo = dc - WINDOW_S * 1e6
        buckets = [0] * int(WINDOW_S * 2)
        others = {}
        advs = []
        for t, flags, d in pk:
            if t < lo or not d:
                continue
            if t > (rc or dc + 60e6):
                break
            if d[0] == 2 and flags & 1:
                h = acl_handle(d)
                if t <= dc and last_rx <= t:
                    others[h] = others.get(h, 0) + 1
                if t <= dc and h == handle:
                    b = int((t - lo) // 500000)
                    if 0 <= b < len(buckets):
                        buckets[b] += 1
            elif t >= dc:
                meta = le_meta(d)
                if meta and adv_from(meta[1], peer):
                    advs.append(t)
        rate = ' '.join(f'{n:3d}' for n in buckets)
        rx = ', '.join(f'0x{h:04x}:{n}' for h, n in others.items()) or '无'
        first = f'断开后 {(advs[0] - dc) / 1e6:.3f} s' if advs else '没收到'
        back = f'{ts(rc):%H:%M:%S.%f}' if rc else '未重连'
        print(f'\n断开 {ts(dc):%H:%M:%S.%f} → 重连 {back}')
        print(f'  断开前 {WINDOW_S:.0f} 秒每 500 ms 收到它的包数: {rate}')
        print(f'  静默期({(dc - last_rx) / 1e6:.1f} s)内收到的各链路包数: {rx}')
        print(f'  它的首条广播: {first}')


def main(argv: list[str]) -> None:
    if len(argv) < 3:
        sys.exit(__doc__)
    peer = bytes.fromhex(argv[1].replace(':', ''))[::-1]
    paths = argv[2:]
    pk = [r for p in paths for r in records(p)]
    print(
        f'{len(pk)} 条记录  {ts(pk[0][0]):%m-%d %H:%M:%S} → {ts(pk[-1][0]):%m-%d %H:%M:%S}'
    )
    events, gaps, drops = timeline(pk, peer)
    print('\n=== 链路事件 ===')
    for t, s in events:
        print(f'{ts(t):%H:%M:%S.%f} {s}')
    print(f'\n=== 收包停顿 ≥ {GAP_MS:.0f} ms(停顿开始时刻, 时长)===')
    for t, g in gaps:
        print(f'{ts(t):%H:%M:%S.%f} {g:8.0f} ms')
    print('\n=== 每次断开的现场 ===')
    drop_windows(pk, peer, drops)


if __name__ == '__main__':
    main(sys.argv)
