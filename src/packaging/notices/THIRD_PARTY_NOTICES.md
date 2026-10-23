# Third-party notices

Prismo is MIT-licensed. The installer also contains the software below, each under its own licence. Full texts are in the licenses folder next to this file.

Collected on 2026-10-24 from each project's own licence file at the matching version tag (or the official project site). Anything that could not be checked against a primary source is marked NOT VERIFIED.

The Windows build is described here. The Linux build uses the same libraries but a different FFmpeg configure line (see section C).

## Acknowledgements

These credits are asked for by the licences below:

- This software is based in part on the work of the Independent JPEG Group (FFmpeg).
- Portions of this software are copyright © 2026 The FreeType Project (https://freetype.org). All rights reserved.
- Prismo's video support uses FFmpeg (https://ffmpeg.org), which includes LAME, the MP3 encoder (https://lame.sourceforge.io, formerly www.mp3dev.org).

## A. Java runtime

The installer carries a cut-down Java runtime made with `jlink`. That runtime has its own `runtime/legal/` folder, with one subfolder per module (for example `java.base`, `javafx.graphics`). Those folders hold every module's full licence and third-party notices, including the code the JDK and JavaFX themselves bundle (zlib, libjpeg, FreeType, HarfBuzz, and others). They are the complete list for the runtime; the table below is only the summary.

| Component | Version | Licence | Copyright holder (as written in its licence) | Source |
|---|---|---|---|---|
| Eclipse Temurin (OpenJDK) | 25.0.4.1+1 (`JAVA_RUNTIME_VERSION="25.0.4.1+1-LTS"` in the local JDK's `release` file) | GPL-2.0 with Classpath Exception (`GPL-2.0-with-Classpath-exception.txt`, `temurin-ASSEMBLY_EXCEPTION.txt`, `openjdk-ADDITIONAL_LICENSE_INFO.txt`) | Free Software Foundation (licence text). The licence file names no code owner; per-module owners are in `runtime/legal/` | https://github.com/adoptium/jdk25u/blob/jdk-25.0.4.1%2B1_adopt/LICENSE , .../ASSEMBLY_EXCEPTION , .../ADDITIONAL_LICENSE_INFO |
| OpenJFX (JavaFX), jmods from Gluon | 25.0.4 (tag `25.0.4+3`, the newest 25.0.4 build tag in openjdk/jfx25u) | GPL-2.0 with Classpath Exception (same LICENSE text as the JDK, byte for byte; `openjfx-ASSEMBLY_EXCEPTION.txt`) | Same as above | https://github.com/openjdk/jfx25u/blob/25.0.4%2B3/LICENSE , .../ASSEMBLY_EXCEPTION |

## B. Java libraries

| Component | Version | Licence | Copyright holder (as written in its licence) | Source |
|---|---|---|---|---|
| metadata-extractor (com.drewnoakes) | 2.21.0 | Apache-2.0 (`metadata-extractor-Apache-2.0.txt`) | Drew Noakes and contributors (2002-2023) | https://github.com/drewnoakes/metadata-extractor/blob/2.21.0/LICENSE |
| xmpcore (com.adobe.xmp) | 6.1.11 | BSD-3-Clause, as declared in its POM and jar manifest (`xmpcore-BSD-3-Clause.txt`). Exact text NOT VERIFIED: no licence file is published with this library | Adobe Systems Incorporated (jar manifest "Bundle-Vendor"). Copyright line NOT VERIFIED | POM: https://repo1.maven.org/maven2/com/adobe/xmp/xmpcore/6.1.11/xmpcore-6.1.11.pom ; text used: https://github.com/adobe/XMP-Toolkit-SDK/blob/main/LICENSE |
| TwelveMonkeys: imageio-core, imageio-jpeg, imageio-webp, imageio-metadata, common-lang, common-io, common-image | 3.15.3 | BSD-3-Clause (`twelvemonkeys-BSD-3-Clause.txt`) | Harald Kuhr (2008-2020) | https://github.com/haraldk/TwelveMonkeys/blob/twelvemonkeys-3.15.3/LICENSE.txt |
| sqlite-jdbc (org.xerial) | 3.53.4.0 | Apache-2.0 (`sqlite-jdbc-Apache-2.0.txt`); original Zentus code under a BSD-2-Clause style licence (`sqlite-jdbc-zentus-BSD-2-Clause.txt`) | Apache file: none named (template line only). Zentus file: David Crawshaw (2006) | https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE , https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE.zentus |
| SQLite (native library inside sqlite-jdbc) | the version bundled in sqlite-jdbc 3.53.4.0 | Public domain (`sqlite-public-domain.txt`) | Dedicated to the public domain by its authors | https://www.sqlite.org/copyright.html |
| SLF4J API | 2.0.20 | MIT (`slf4j-MIT.txt`) | QOS.ch Sarl (Switzerland), 2004-2022 | https://github.com/qos-ch/slf4j/blob/v_2.0.20/LICENSE.txt |
| Logback (logback-classic, logback-core) | 1.6.5 | Dual: EPL-2.0 or LGPL-2.1, at the user's choice (`logback-EPL-2.0-OR-LGPL-2.1.txt`, `EPL-2.0.txt`, `LGPL-2.1.txt`). Note: EPL **v2.0**, not v1.0 | QOS.ch, 1999-2024 | https://github.com/qos-ch/logback/blob/v_1.6.5/LICENSE.txt |
| JavaCV (org.bytedeco) | 1.5.14 | Dual: Apache-2.0, or GPL-2.0-or-later with Classpath Exception. Prismo uses it under Apache-2.0 (`bytedeco-javacv-javacpp-Apache-2.0-OR-GPL-2.0-with-Classpath-exception.txt`) | The licence file names no holder. Source file headers say "Copyright (C) 2009-2025 Samuel Audet" (checked in `FFmpegFrameGrabber.java` from the 1.5.14 sources jar) | https://github.com/bytedeco/javacv/blob/1.5.14/LICENSE.txt |
| JavaCPP (org.bytedeco), incl. its windows-x86_64 jar | 1.5.14 | Same dual licence, used under Apache-2.0 (same file; byte-identical to JavaCV's) | Source headers: "Copyright (C) 2011-2023 Samuel Audet" (`Loader.java`) | https://github.com/bytedeco/javacpp/blob/1.5.14/LICENSE.txt |
| FFmpeg Java bindings (org.bytedeco:ffmpeg) | 8.1.2-1.5.14 | Same dual licence, used under Apache-2.0 (same file; byte-identical) | Source headers: "Copyright (C) 2013-2025 Samuel Audet" (`presets/avutil.java`) | https://github.com/bytedeco/javacpp-presets/blob/1.5.14/LICENSE.txt |
| Microsoft Visual C++ and Universal C runtime DLLs (inside the JavaCPP windows-x86_64 jar: `msvcp140*.dll`, `vcruntime140*.dll`, `concrt140.dll`, `vcomp140.dll`, `libomp140.x86_64.dll`, `ucrtbase.dll`, `api-ms-win-*.dll`) | not stated | Microsoft's own files, passed on unchanged as they come inside JavaCPP's jar (the Java runtime in `runtime/bin` carries the same kind of files from Eclipse Temurin). Microsoft says these files may be redistributed by licensed Visual Studio users under its licence terms ("Distributable Code", REDIST list). Our reading: that covers the builders of those jars and of Temurin; no separate licence text comes with the files | Microsoft Corporation | https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files |

## C. FFmpeg native build and the libraries built into it

The FFmpeg DLLs come from the org.bytedeco:ffmpeg 8.1.2-1.5.14 windows-x86_64 jar. They were built by the javacpp-presets 1.5.14 script `ffmpeg/cppbuild.sh`.

Checked in the shipped `avutil-60.dll` itself: its embedded build line contains `--enable-version3` and no `--enable-gpl` and no `--enable-nonfree`, and its embedded licence string is "LGPL version 3 or later". The full Windows configure line is:

```
--prefix=.. --disable-iconv --disable-opencl --disable-sdl2 --disable-bzlib --disable-lzma --disable-linux-perf --disable-xlib --enable-shared --enable-version3 --enable-runtime-cpudetect --enable-zlib --enable-libmp3lame --enable-libspeex --enable-libopencore-amrnb --enable-libopencore-amrwb --enable-libvo-amrwbenc --enable-openssl --enable-libopenh264 --enable-libvpx --enable-libfreetype --enable-libharfbuzz --enable-libopus --enable-libxml2 --enable-libsrt --enable-libwebp --enable-libaom --enable-libsvtav1 --enable-libzimg --enable-vulkan --enable-hwaccel=h264_vulkan --enable-hwaccel=hevc_vulkan --enable-hwaccel=av1_vulkan --enable-cuda --enable-cuvid --enable-nvenc --enable-libmfx --enable-w32threads --enable-indev=dshow --target-os=mingw32 --cc='gcc -m64' --cxx='g++ -m64' --extra-cflags='-DLIBXML_STATIC -I../include/ -I../include/libxml2 -I../include/mfx/ -I../include/svt-av1' --extra-ldflags=-L../lib/ --extra-libs='-static-libgcc -static-libstdc++ -Wl,-Bstatic -lstdc++ -lgcc_eh -lWs2_32 -lcrypt32 -lpthread -lz -lm -Wl,-Bdynamic -lole32 -luuid'
```

(Linux build, from the same cppbuild.sh: same library list, plus `--enable-libdrm --enable-pthreads --enable-libxcb --enable-libpulse`, and no `--enable-libmfx`. Not checked against a shipped Linux .so.)

Versions are the variables at the top of cppbuild.sh at tag 1.5.14. Where the shipped DLLs carry a version string, it was also checked there (marked "seen in DLL").

| Component | Version | Licence | Copyright holder (as written in its licence) | Source |
|---|---|---|---|---|
| FFmpeg (avcodec, avformat, avutil, avfilter, avdevice, swscale, swresample DLLs, plus ffmpeg.exe and ffprobe.exe that are also in the jar) | 8.1.2 | LGPL-3.0-or-later for this build (`LGPL-3.0.txt`, which builds on `GPL-3.0.txt`; FFmpeg's own explanation in `ffmpeg-LICENSE.txt`). Built with three patches from javacpp-presets (see section D) | The FFmpeg developers (licence file names no single holder). FFmpeg's LICENSE.md says three files come from libjpeg and "you must credit the IJG in the documentation accompanying your program if you only distribute executables": This software is based in part on the work of the Independent JPEG Group. | https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/LICENSE.md , https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/COPYING.LGPLv3 ; shared text: https://www.gnu.org/licenses/lgpl-3.0.txt |
| zlib | 1.3.2 | Zlib (`zlib-Zlib.txt`) | Jean-loup Gailly and Mark Adler (1995-2026) | https://github.com/madler/zlib/blob/v1.3.2/LICENSE |
| LAME (libmp3lame) | 3.100 (seen in DLL); patched with javacpp-presets `ffmpeg/lame.patch` | LGPL-2.0-or-later ("version 2 of the License, or (at your option) any later version", from `include/lame.h`) (`lame-LGPL-2.0-or-later.txt`; LAME's own notes in `lame-LICENSE-notes.txt`) | Mark Taylor (1999), from the `include/lame.h` header; the COPYING file names no code owner | COPYING and LICENSE inside https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz |
| Speex | 1.2.1 | BSD-3-Clause (`speex-BSD-3-Clause.txt`) | Xiph.org Foundation, Jean-Marc Valin, Analog Devices Inc., CSIRO, David Rowe, EpicGames, Jutta Degener, Carsten Bormann | https://github.com/xiph/speex/blob/Speex-1.2.1/COPYING |
| opencore-amr (amrnb, amrwb) | 0.1.6 | Apache-2.0 (`Apache-2.0.txt`) with its NOTICE file (`opencore-amr-NOTICE.txt`, which also carries 3GPP / ISO MPEG-4 contribution notices) | PacketVideo (2009), plus the contributors listed in the NOTICE | LICENSE and opencore/NOTICE inside https://sourceforge.net/projects/opencore-amr/files/opencore-amr/opencore-amr-0.1.6.tar.gz/download |
| vo-amrwbenc | 0.1.3 | Apache-2.0 (`vo-amrwbenc-NOTICE.txt`, which includes the full Apache text) | VisualOn (2003-2010) | NOTICE and COPYING inside https://sourceforge.net/projects/opencore-amr/files/vo-amrwbenc/vo-amrwbenc-0.1.3.tar.gz/download |
| OpenSSL | 3.5.7 (seen in DLL) | Apache-2.0 (`openssl-Apache-2.0.txt`) | The licence file names no holder | https://github.com/openssl/openssl/blob/openssl-3.5.7/LICENSE.txt |
| OpenH264 | 2.6.0 | BSD-2-Clause (`openh264-BSD-2-Clause.txt`) | Cisco Systems (2013) | https://github.com/cisco/openh264/blob/v2.6.0/LICENSE |
| libvpx (VP8/VP9) | 1.16.0 (seen in DLL) | BSD-3-Clause (`libvpx-BSD-3-Clause.txt`) plus Google's patent grant (`libvpx-PATENTS.txt`) | The WebM Project authors (2010) | https://github.com/webmproject/libvpx/blob/v1.16.0/LICENSE , .../PATENTS |
| FreeType | 2.14.3 | FreeType License (FTL), chosen from its FTL-or-GPLv2 dual licence (`freetype-FTL.txt`, `freetype-LICENSE.txt`). FTL asks for this credit in the documentation, with the year filled in: "Portions of this software are copyright © <year> The FreeType Project (https://freetype.org). All rights reserved." (2026: FreeType 2.14.3's own source headers say "Copyright (C) 1996-2026"; the credit is in Acknowledgements above) | David Turner, Robert Wilhelm, and Werner Lemberg (FTL text) | https://gitlab.freedesktop.org/freetype/freetype/-/blob/VER-2-14-3/LICENSE.TXT , .../docs/FTL.TXT |
| HarfBuzz | 14.3.0 | "Old MIT" (`harfbuzz-Old-MIT.txt`) | Google, Inc.; Ebrahim Byagowi; Facebook, Inc.; Mozilla Foundation; Red Hat, Inc.; Behdad Esfahbod; and others listed in the file | https://github.com/harfbuzz/harfbuzz/blob/14.3.0/COPYING |
| Opus | 1.3.1 | BSD-3-Clause (`opus-BSD-3-Clause.txt`), with royalty-free patent licences listed in the file | Xiph.Org, Skype Limited, Octasic, Jean-Marc Valin, Timothy B. Terriberry, CSIRO, Gregory Maxwell, Mark Borgerding, Erik de Castro Lopo | https://github.com/xiph/opus/blob/v1.3.1/COPYING |
| libxml2 | 2.9.12 | MIT (`libxml2-MIT.txt`) | Daniel Veillard (1998-2012) | https://github.com/GNOME/libxml2/blob/v2.9.12/Copyright |
| SRT (libsrt) | 1.5.6 (seen in DLL) | MPL-2.0 (`srt-MPL-2.0.txt`) | The licence file names no holder | https://github.com/Haivision/srt/blob/v1.5.6/LICENSE |
| libwebp | 1.6.0 | BSD-3-Clause (`libwebp-BSD-3-Clause.txt`) plus Google's patent grant (`libwebp-PATENTS.txt`) | Google Inc. (2010) | https://github.com/webmproject/libwebp/blob/v1.6.0/COPYING , .../PATENTS |
| libaom (AV1) | 3.14.1 (seen in DLL) | BSD-2-Clause (`libaom-BSD-2-Clause.txt`) plus the Alliance for Open Media Patent License 1.0 (`libaom-AOM-Patent-License-1.0.txt`), which must be reproduced with binaries | Alliance for Open Media (2016) | https://aomedia.googlesource.com/aom/+/refs/tags/v3.14.1/LICENSE , .../PATENTS |
| SVT-AV1 | 4.2.0 | BSD-3-Clause-Clear (`svt-av1-BSD-3-Clause-Clear.txt`) plus the AOM Patent License 1.0 (`svt-av1-AOM-Patent-License-1.0.txt`) | Alliance for Open Media (2021) | https://gitlab.com/AOMediaCodec/SVT-AV1/-/blob/v4.2.0/LICENSE.md , .../PATENTS.md |
| zimg | 3.0.6 | WTFPL (`zimg-WTFPL.txt`) | Sam Hocevar (2004), for the licence text | https://github.com/sekrit-twc/zimg/blob/release-3.0.6/COPYING |
| Intel Media SDK dispatcher (lu-zero/mfx_dispatch, for `--enable-libmfx`; Windows build only) | 1.35.1 | BSD-3-Clause (`mfx_dispatch-BSD-3-Clause.txt`) | Intel Corporation (2012) | https://github.com/lu-zero/mfx_dispatch/blob/1.35.1/LICENSE |
| nv-codec-headers (for CUDA / CUVID / NVENC; headers only, the NVIDIA driver supplies the actual code at run time) | 13.1.15.0 | MIT (`nv-codec-headers-MIT.txt`, the notice from the top of `nvEncodeAPI.h`) | NVIDIA Corporation (2010-2026) | https://github.com/FFmpeg/nv-codec-headers/blob/n13.1.15.0/include/ffnvcodec/nvEncodeAPI.h |
| Vulkan headers (for `--enable-vulkan`; headers only, the GPU driver supplies `vulkan-1.dll` at run time) | NOT VERIFIED: cppbuild.sh does not download them, so they came from the build machine's MSYS2 packages | Apache-2.0 OR MIT (`vulkan-headers-Apache-2.0-OR-MIT.txt`, from the current main branch, not a matching tag) | The Khronos Group Inc. | https://github.com/KhronosGroup/Vulkan-Headers/blob/main/LICENSE.md |
| GCC runtime (libgcc, libgcc_eh, libstdc++), linked statically (`-static-libgcc -static-libstdc++`) | MSYS2 builds of GCC 14.2.0 and 15.1.0 (strings "GCC: (Rev3, Built by MSYS2 project) 14.2.0" and "GCC: (Rev5, Built by MSYS2 project) 15.1.0" seen in the DLLs) | GPL-3.0 with the GCC Runtime Library Exception 3.1 (`GPL-3.0.txt`, `GCC-exception-3.1.txt`). The exception means the compiled program is not bound by the GPL | Free Software Foundation (licence and exception text) | https://github.com/gcc-mirror/gcc/blob/releases/gcc-15.1.0/COPYING.RUNTIME |
| MinGW-w64 runtime (CRT start-up code, linked statically) | NOT VERIFIED (toolchain version only known from the GCC strings above) | Several permissive licences, listed in `mingw-w64-runtime.txt` | the mingw-w64 project (2009-2013) and others listed in the file | https://github.com/mingw-w64/mingw-w64/blob/master/COPYING.MinGW-w64-runtime/COPYING.MinGW-w64-runtime.txt (master branch, not a matching tag) |
| MinGW-w64 winpthreads (`libwinpthread-1.dll`, a separate DLL in the FFmpeg jar) | NOT VERIFIED (built with MSYS2 GCC 15.1.0, seen in the DLL) | MIT, plus the older notices quoted in the same file (`mingw-w64-winpthreads-MIT.txt`) | mingw-w64 project (2011) | https://github.com/mingw-w64/mingw-w64/blob/master/mingw-w64-libraries/winpthreads/COPYING (master branch, not a matching tag) |

Not included in this build: x264 and x265 (GPL). cppbuild.sh only turns them on for its separate `-gpl` build, and the shipped configure line above has neither `--enable-libx264` nor `--enable-libx265`.

## D. Getting the FFmpeg source

FFmpeg is licensed under the GNU Lesser General Public License, version 3 or later, in this build. You can get the exact source used:

- FFmpeg 8.1.2 source: https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz (checked on 2026-10-24: the file exists, 11,710,924 bytes; the signature file https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz.asc also exists).
- The build script and the patches applied to FFmpeg and LAME: javacpp-presets 1.5.14, folder `ffmpeg/`, at https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg . The script is `cppbuild.sh`. It applies `ffmpeg.patch`, `ffmpeg-vulkan.patch` and `ffmpeg-v4l2-request-20847-ffmpeg-8.1.patch` to FFmpeg, and `lame.patch` to LAME (all four checked to exist at that tag). The script's version variables list where every other library's source comes from.

The FFmpeg libraries ship as separate DLL files (Windows) or .so files (Linux), not built into Prismo's own code. They sit inside the jar `ffmpeg-8.1.2-1.5.14-windows-x86_64.jar` (a zip file) in the app folder, and JavaCPP unpacks them to its cache folder when Prismo starts. You can replace them with your own build of the same FFmpeg version by swapping the files inside that jar.

## Where the shared licence texts came from

- `Apache-2.0.txt`: https://www.apache.org/licenses/LICENSE-2.0.txt
- `LGPL-3.0.txt`, `GPL-3.0.txt`, `GPL-2.0.txt`: https://www.gnu.org/licenses/ (lgpl-3.0.txt, gpl-3.0.txt, old-licenses/gpl-2.0.txt)
- `LGPL-2.1.txt`: gnu.org could not be reached on 2026-10-24, so this is FFmpeg's copy of the GNU text, https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/COPYING.LGPLv2.1 (re-check against https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt)
- `GCC-exception-3.1.txt`: gnu.org could not be reached, so this is GCC's own copy, https://github.com/gcc-mirror/gcc/blob/releases/gcc-15.1.0/COPYING.RUNTIME (re-check against https://www.gnu.org/licenses/gcc-exception-3.1.txt)
- `EPL-2.0.txt`: https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt
- `MPL-2.0.txt`: https://www.mozilla.org/media/MPL/2.0/index.txt
- `GPL-2.0-with-Classpath-exception.txt`: the OpenJDK LICENSE file listed in section A
