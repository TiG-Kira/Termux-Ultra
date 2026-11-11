# Notice

This directory contains a verbatim copy of the `v4.0/` tree from
`https://github.com/microsoft/MS-DOS` (source code and `LICENSE` only).

    MIT License
    Copyright (c) IBM and Microsoft Corporation.

The full license text is preserved at `v4.0/LICENSE` and must be retained in
any distribution of the source or of binaries built from it.

The CI build applies a checksum-selected set of fixes from
`https://github.com/tgies/MS-DOS` at commit
`c8d7ddfe720df811e8c124810a16e34a71cc63d4`. These fix CP437-to-UTF-8
transcription damage in the upstream release and are distributed under the
same MIT terms; the build scripts from that project are marked Public Domain.

The initial `msdos-v1` prerelease disk asset derives from `msdos4.img` in
`https://github.com/tgies/MS-DOS/releases/tag/4.0-c860389`, a binary built from
the same MIT-licensed source tree. Its unused 64 MB tail was truncated to 16 MB
by rewriting only the partition-table sector count and the BPB total-sector
fields; all boot code and system files remain byte-for-byte unchanged. It carries
the same MIT obligations and is replaced by this project's own CI build once the
workflow has been verified.

Configuration files under `termux/config/`, build scripts under `build/` and
the App-side Kotlin code are original work of this project.

`MS-DOS` is a trademark of Microsoft Corporation. This feature is an
independent, unofficial adaptation and is not endorsed by or affiliated with
Microsoft or IBM. The name is used only to describe the historical system
whose source code is being built.
