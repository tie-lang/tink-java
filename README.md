# tink-java

tink data-flow node frame protocol — Java 17 library (no dependencies,
package `org.tielang.tink`). Universal and language-agnostic: any component
that obeys the frame protocol can join a tink pipeline.

```
帧 = [ len: u32 BE ][ payload: len 字节 ][ crc: u32 BE ]
len = payload 字节数
crc = CRC32-IEEE(payload)（多项式 0xEDB88320）
```

Mirrors `std/tink.tie` (tie standard library) and the Rust / C / Python / JS /
C++ tink libraries; pure functions over byte arrays, IO (stdin/stdout) left to
the caller.

## API (`org.tielang.tink.Tink`)

| method | description |
| --- | --- |
| `crc32(byte[]) -> long` | CRC32-IEEE over a byte array. Check vector: `crc32("123456789") == 0xCBF43926` |
| `frameEncode(byte[]) -> byte[]` | encode a payload into a full frame `[len][payload][crc]` |
| `frameNext(byte[], int) -> Optional<Frame>` | parse one frame at `pos`, verify CRC; `Frame(payload, next)` on success, empty on out-of-bounds / mismatch |
| `frameSkip(byte[], int) -> OptionalLong` | skip one frame at `pos` without copying or verifying; empty on out-of-bounds |

`Frame` is a record holding `byte[] payload` (a copy) and `int next`.

## Usage

```java
import org.tielang.tink.Tink;

byte[] frame = Tink.frameEncode(new byte[]{1, 2, 3});
var got = Tink.frameNext(frame, 0); // Optional<Tink.Frame>
```

## Build & test

No build step required for the single class:

```bash
javac -d out src/main/java/org/tielang/tink/Tink.java \
          src/test/java/org/tielang/tink/TestTink.java
java -cp out org.tielang.tink.TestTink
```

Or with Maven:

```bash
mvn compile
```

## Cross-language

tink 帧协议各语言实现（API 语义与校验向量一致）：

| language | library |
| --- | --- |
| tie | `std/tink.tie` |
| Rust | `tink-rust`（tink crate） |
| C | `tink-c`（`tink.h` + `tink.c`） |
| Python | `tink-python`（`tink.py`） |
| JavaScript | `tink-js`（`tink.js` + `tink.d.ts`） |
| C++ | `tink-cpp`（`tink.hpp`） |
| Java | this library（`tink-java`） |

## License

本仓库使用 **Tie Public License v2.0 (TPL 2.0)**，完整文本见 [LICENSE](LICENSE)。
This repository is distributed under the **Tie Public License v2.0 (TPL 2.0)** — see [LICENSE](LICENSE) for the full text.