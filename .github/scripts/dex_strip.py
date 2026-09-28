#!/usr/bin/env python3
"""
DEX 内部 debug_info 瘦身（零重编译、零依赖）

目标：剥离 debug_info_item 里「运行时不需要」的 entry，只保留构造异常堆栈
StackTraceElement.lineNumber 所需的信息。

保留什么（运行时必需）：
  - line_start（代码起始行号，ULEB128 编码）
  - parameters_size + parameter_names（形参表的 ULEB128 编码 string_id，原样保留）
  - 行号 opcode：special opcode (0x0a..0xff)、DBG_ADVANCE_PC(0x01)、DBG_ADVANCE_LINE(0x02)
  - END_SEQUENCE 终止标记

删除什么（运行时无用，IDE 调试专用）：
  - DBG_START_LOCAL / DBG_START_LOCAL_EXTENDED / DBG_END_LOCAL / DBG_RESTART_LOCAL  局部变量表
  - DBG_SET_PROLOGUE_END / DBG_SET_EPILOGUE_BEGIN  栈帧标记
  - DBG_SET_SOURCE_FILE  切换源文件（DEX header 已有 source_idx）

⚠️  不动字节码、不动常量池、不重编译 DEX；所有方法 / 类 / 字符串 / 类型引用保持原样。
    唯一需要修正的 offset 是 CodeItem.debug_info_off（因为 debug_info_item 被搬到了
    文件末尾的紧凑区域，旧偏移全部失效）。

用法: dex_strip.py <in.dex> <out.dex>
"""
import os
import struct
import sys
import zlib


# ── DEX debug opcode 常量（来自 androguard / Android DEX 规范）────────
DBG_END_SEQUENCE          = 0x00
DBG_ADVANCE_PC            = 0x01   # advance PC (uleb128) — 不产生 position entry
DBG_ADVANCE_LINE          = 0x02   # advance_line (sleb128) — 不产生 position entry
DBG_START_LOCAL           = 0x03   # 局部变量（删除）
DBG_START_LOCAL_EXTENDED  = 0x04   # 局部变量 + 签名（删除）
DBG_END_LOCAL             = 0x05   # 局部变量结束（删除）
DBG_RESTART_LOCAL         = 0x06   # 局部变量重启（删除）
DBG_SET_PROLOGUE_END      = 0x07   # 栈帧标记（删除）
DBG_SET_EPILOGUE_BEGIN    = 0x08   # 栈帧标记（删除）
DBG_SET_SOURCE_FILE       = 0x09   # 源文件（删除）
# DBG_Special_Opcodes 范围: 0x0a..0xff（advance_pc + advance_line 合并编码，单行 opcode）


# ── SLEB128 / ULEB128 编解码 ───────────────────────────────────────
def _uleb128_read(buf, off):
    result, shift = 0, 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7f) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
        if shift >= 35:
            raise ValueError("ULEB128 too long")
    return result, off


def _sleb128_read(buf, off):
    result, shift, size = 0, 0, 32
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7f) << shift
        shift += 7
        if (b & 0x80) == 0:
            if shift < size and (b & 0x40):
                result |= -(1 << shift)
            break
        if shift >= size:
            raise ValueError("SLEB128 too long")
    return result, off


def _uleb128_encode(val):
    out = bytearray()
    while True:
        b = val & 0x7f
        val >>= 7
        if val == 0 and not (b & 0x40):
            out.append(b)
            break
        out.append(b | 0x80)
    return bytes(out)


def _sleb128_encode(val):
    out = bytearray()
    while True:
        b = val & 0x7f
        val >>= 7
        sign_bit = b & 0x40
        done = (val == 0 and not sign_bit) or (val == -1 and sign_bit)
        if done:
            out.append(b)
            break
        out.append(b | 0x80)
    return bytes(out)


# ── 解析一个完整 debug_info_item，返回 (header_bytes, slimmed_opcodes, old_size) ─
def _parse_debug_info(buf: bytes, start: int):
    """
    从 buf[start:] 开始解析一个完整的 debug_info_item：
      uleb128 line_start
      uleb128 parameters_size
      uleb128p1 parameter_names[parameters_size]
      u1[] debug_opcodes   ← 直到遇到 DBG_END_SEQUENCE (0x00)

    返回: (old_size, header_bytes, opcode_bytes_including_END_SEQUENCE)
    失败返回 None
    """
    try:
        off = start
        line_start, off = _uleb128_read(buf, off)
        parameters_size, off = _uleb128_read(buf, off)
        param_names_start = off
        for _ in range(parameters_size):
            _, off = _uleb128_read(buf, off)
        header_end = off
        header_bytes = bytes(buf[start:header_end])

        # 解析 opcodes 直到 END_SEQUENCE
        opcode_start = header_end
        while off < len(buf):
            op = buf[off]
            off += 1
            if op == DBG_END_SEQUENCE:
                off += 0   # END_SEQUENCE 本身已经读过
                break
            if op == DBG_ADVANCE_PC:
                _, off = _uleb128_read(buf, off)
            elif op == DBG_ADVANCE_LINE:
                _, off = _sleb128_read(buf, off)
            elif op == DBG_START_LOCAL:
                _, off = _uleb128_read(buf, off)
                _, off = _uleb128_read(buf, off)
                _, off = _uleb128_read(buf, off)
            elif op == DBG_START_LOCAL_EXTENDED:
                _, off = _uleb128_read(buf, off)
                _, off = _uleb128_read(buf, off)
                _, off = _uleb128_read(buf, off)
                _, off = _uleb128_read(buf, off)
            elif op in (DBG_END_LOCAL, DBG_RESTART_LOCAL):
                _, off = _uleb128_read(buf, off)
            elif op == DBG_SET_SOURCE_FILE:
                _, off = _uleb128_read(buf, off)
            elif op in (DBG_SET_PROLOGUE_END, DBG_SET_EPILOGUE_BEGIN):
                pass   # 无参数
            elif op >= 0x0a:
                pass   # special opcode (0x0a..0xff), 无参数
            else:
                # 未知 opcode，安全起见停在这里
                return None

        old_size = off - start
        opcode_bytes = bytes(buf[opcode_start:off])
        return old_size, header_bytes, opcode_bytes

    except (IndexError, ValueError):
        return None


def _shrink_debug_info(buf: bytes, start: int):
    """
    解析 + 瘦身 debug_info_item，返回 (slimmed_bytes, old_size)；失败返回 None。
    """
    parsed = _parse_debug_info(buf, start)
    if parsed is None:
        return None
    old_size, header_bytes, opcode_bytes = parsed

    # 过滤 opcodes
    new_ops = bytearray()
    off = 0
    while off < len(opcode_bytes):
        op = opcode_bytes[off]
        off += 1

        if op == DBG_END_SEQUENCE:
            new_ops.append(op)
            break

        if op == DBG_ADVANCE_PC:
            val, off = _uleb128_read(opcode_bytes, off)
            new_ops.append(op)
            new_ops.extend(_uleb128_encode(val))
            continue

        if op == DBG_ADVANCE_LINE:
            val, off = _sleb128_read(opcode_bytes, off)
            new_ops.append(op)
            new_ops.extend(_sleb128_encode(val))
            continue

        # 删除局部变量 / 栈帧 / 源文件
        if op == DBG_START_LOCAL:
            _, off = _uleb128_read(opcode_bytes, off)
            _, off = _uleb128_read(opcode_bytes, off)
            _, off = _uleb128_read(opcode_bytes, off)
            continue
        if op == DBG_START_LOCAL_EXTENDED:
            _, off = _uleb128_read(opcode_bytes, off)
            _, off = _uleb128_read(opcode_bytes, off)
            _, off = _uleb128_read(opcode_bytes, off)
            _, off = _uleb128_read(opcode_bytes, off)
            continue
        if op in (DBG_END_LOCAL, DBG_RESTART_LOCAL):
            _, off = _uleb128_read(opcode_bytes, off)
            continue
        if op == DBG_SET_SOURCE_FILE:
            _, off = _uleb128_read(opcode_bytes, off)
            continue
        if op in (DBG_SET_PROLOGUE_END, DBG_SET_EPILOGUE_BEGIN):
            continue

        # special opcode (0x0a..0xff) — 单行 opcode，保留
        if op >= 0x0a:
            new_ops.append(op)
            continue

        # 未知 opcode，保守保留
        new_ops.append(op)

    if not new_ops or new_ops[-1] != DBG_END_SEQUENCE:
        return None

    result = bytearray()
    result.extend(header_bytes)   # line_start + params_size + param_names 原样
    result.extend(new_ops)
    return bytes(result), old_size


# ── 主流程 ──────────────────────────────────────────────────────────
def main():
    if len(sys.argv) != 3:
        print("usage: dex_strip.py <in.dex> <out.dex>", file=sys.stderr)
        sys.exit(1)

    in_path, out_path = sys.argv[1], sys.argv[2]
    with open(in_path, 'rb') as f:
        dex = bytearray(f.read())

    if len(dex) < 0x70 or dex[0:3] != b'dex':
        print(f"不是有效 DEX: {in_path}", file=sys.stderr)
        sys.exit(1)

    file_size     = struct.unpack_from('<I', dex, 0x20)[0]
    header_size   = struct.unpack_from('<I', dex, 0x24)[0]
    endian_tag    = struct.unpack_from('<I', dex, 0x28)[0]
    if endian_tag != 0x12345678:
        print(f"非 little-endian DEX，endian_tag=0x{endian_tag:08x}，不支持", file=sys.stderr)
        sys.exit(1)

    def section(base_off):
        size, off = struct.unpack_from('<II', dex, base_off)
        return off, size

    _, _           = section(0x38)   # string_ids
    _, _           = section(0x40)   # type_ids
    _, _           = section(0x48)   # proto_ids
    _, _           = section(0x50)   # field_ids
    _, _           = section(0x58)   # method_ids
    class_defs_off, class_defs_size = section(0x60)

    print(f"  DEX: class_defs_off=0x{class_defs_off:04x} class_defs_size={class_defs_size} file_size={file_size}")

    code_offset_to_debug_off = {}
    all_debug_offsets = set()

    for ci in range(class_defs_size):
        cd_off = class_defs_off + ci * 32
        class_data_off = struct.unpack_from('<I', dex, cd_off + 24)[0]
        if class_data_off == 0:
            continue

        off = class_data_off
        sf, off = _uleb128_read(dex, off)
        inf, off = _uleb128_read(dex, off)
        dm, off = _uleb128_read(dex, off)
        vm, off = _uleb128_read(dex, off)

        for _ in range(sf + inf):
            _, off = _uleb128_read(dex, off)
            _, off = _uleb128_read(dex, off)

        for _ in range(dm + vm):
            _, off = _uleb128_read(dex, off)
            _, off = _uleb128_read(dex, off)
            code_off, off = _uleb128_read(dex, off)
            if code_off == 0:
                continue
            # CodeItem.debug_info_off 在 CodeItem + 0x08（u4 小端）
            debug_off = struct.unpack_from('<I', dex, code_off + 0x08)[0]
            if debug_off != 0:
                code_offset_to_debug_off[code_off] = debug_off
                all_debug_offsets.add(debug_off)

    print(f"[dex_strip] 扫描到 {len(code_offset_to_debug_off)} 个带 debug_info 的方法")
    print(f"[dex_strip] 去重后 {len(all_debug_offsets)} 个 debug_info_item")

    if not all_debug_offsets:
        with open(out_path, 'wb') as f:
            f.write(dex)
        print("[dex_strip] 无 debug_info，原样复制")
        return

    # ── 瘦身每个 debug_info_item ──
    new_debug_base = file_size + (4 - file_size % 4) % 4
    old_to_new_offset = {}
    slimmed = {}     # old_off → (slimmed_bytes, old_size)
    cursor = new_debug_base
    total_old = 0; total_new = 0

    dex_bytes = bytes(dex)   # 转成只读 bytes 方便 slicing

    for old_off in sorted(all_debug_offsets):
        result = _shrink_debug_info(dex_bytes, old_off)
        if result is None:
            # 解析失败 → 用 _parse_debug_info 拿原始，原样保留
            parsed = _parse_debug_info(dex_bytes, old_off)
            if parsed is None:
                # 彻底不行，跳过（不应发生）
                print(f"[dex_strip] ⚠️  debug_info @0x{old_off:04x} 解析失败，跳过", file=sys.stderr)
                continue
            old_sz, hdr, ops = parsed
            slim = hdr + ops
        else:
            slim, old_sz = result
        old_to_new_offset[old_off] = cursor
        slimmed[old_off] = (slim, old_sz)
        cursor += len(slim)
        total_old += old_sz
        total_new += len(slim)

    saved = total_old - total_new
    pct = (saved / total_old * 100) if total_old > 0 else 0
    print(f"[dex_strip] debug_info 总大小: {total_old:,} → {total_new:,} (省 {saved:,} B, {pct:.1f}%)")

    # ── 写新 DEX ──
    new_dex = bytearray(dex[:file_size])
    for code_off, old_debug_off in code_offset_to_debug_off.items():
        new_debug_off = old_to_new_offset.get(old_debug_off, old_debug_off)
        struct.pack_into('<I', new_dex, code_off + 0x08, new_debug_off)
    for old_off in sorted(slimmed.keys()):
        new_dex.extend(slimmed[old_off][0])
    struct.pack_into('<I', new_dex, 0x20, len(new_dex))

    # 更新 Adler32 checksum（DEX 规范：覆盖 dex[0x0c:]，即跳过 magic[8] + checksum[4]）
    checksum = zlib.adler32(bytes(new_dex[0x0c:])) & 0xffffffff
    struct.pack_into('<I', new_dex, 0x08, checksum)

    with open(out_path, 'wb') as f:
        f.write(new_dex)

    print(f"[dex_strip] 输出: {out_path} ({len(new_dex):,} bytes，原 {file_size:,})")
    print(f"[dex_strip] ✅ debug_info 瘦身完成，行号表 100% 保留")


if __name__ == "__main__":
    main()
