# kotlin-nds

Kotlin Multiplatform utilities to work with .nds files

[![License](https://img.shields.io/github/license/nathanfallet/kotlin-nds)](LICENSE)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.nathanfallet.nds/kotlin-nds)](https://klibs.io/project/nathanfallet/kotlin-nds)
[![Issues](https://img.shields.io/github/issues/nathanfallet/kotlin-nds)]()
[![Pull Requests](https://img.shields.io/github/issues-pr/nathanfallet/kotlin-nds)]()

## Features

- **Parse & repack NDS ROMs** — Read ROM headers, ARM binaries, overlays, banner and all filesystem files; serialize
  back with automatic offset and CRC recalculation.
- **NARC archive support** — Unpack and repack Nintendo DS NARC container files.
- **BLZ compression** — Compress and decompress ARM9 binaries and overlay files using the Bottom-LZ codec.
- **Multiplatform** — Runs on JVM, JavaScript (Node.js & browser), and Native (macOS, Linux, iOS, Windows, …).

## Installation

```kotlin
dependencies {
    implementation("me.nathanfallet.nds:kotlin-nds:1.0.0")
}
```

## Usage

### Parsing an NDS ROM

```kotlin
val romBytes: ByteArray = File("game.nds").readBytes()
val rom = NdsRom.parse(romBytes)

println(rom.gameTitle)  // e.g. "MY GAME"
println(rom.gameCode)   // e.g. "ABCD"
println(rom.files.keys) // e.g. [a/0/0/0, a/0/0/1, ...]
```

### Reading a file from the ROM filesystem

```kotlin
val rom = NdsRom.parse(File("game.nds").readBytes())

// Files are keyed by their virtual path inside the ROM
val fileData: ByteArray? = rom.files["a/0/3/2"]
```

### Modifying a file and repacking

```kotlin
val rom = NdsRom.parse(File("game.nds").readBytes())

// Replace a single file (returns a new NdsRom — original is unchanged)
val modifiedRom = rom.withFile("a/0/3/2", newFileBytes)

// Write the modified ROM to disk
File("game_modified.nds").writeBytes(modifiedRom.pack())
```

### Updating multiple files at once

```kotlin
val rom = NdsRom.parse(File("game.nds").readBytes())

val modifiedRom = rom.withFiles(
    mapOf(
        "a/0/3/2" to newFileBytes,
        "a/0/3/3" to anotherFileBytes,
    )
)

File("game_modified.nds").writeBytes(modifiedRom.pack())
```

### Replacing the ARM7/ARM9 binary

```kotlin
val rom = NdsRom.parse(File("game.nds").readBytes())
val newArm7 = File("arm7_patched.bin").readBytes()
val newArm9 = File("arm9_patched.bin").readBytes()

val modifiedRom = rom.withArm7(newArm7).withArm9(newArm9)
File("game_modified.nds").writeBytes(modifiedRom.pack())
```

---

### NARC Archives

NARC files are container archives used by many DS games to bundle assets. Files inside a NARC are accessed by index (no
names).

#### Unpacking a NARC

```kotlin
val narcBytes = rom.files["a/0/3/2"] ?: error("file not found")
val files: List<ByteArray> = NarcArchive.unpack(narcBytes)

files.forEachIndexed { index, data ->
    File("out/$index.bin").writeBytes(data)
}
```

#### Repacking a NARC

```kotlin
val files = (0..9).map { index -> File("out/$index.bin").readBytes() }
val newNarc: ByteArray = NarcArchive.pack(files)

val modifiedRom = rom.withFile("a/0/3/2", newNarc)
File("game_modified.nds").writeBytes(modifiedRom.pack())
```

---

### BLZ Compression

The Bottom-LZ (BLZ) codec is used by the DS to compress ARM9 binaries and overlay files.

#### Decompressing

```kotlin
val compressedArm9 = File("arm9_compressed.bin").readBytes()
val decompressed: ByteArray = BlzCodec.decompress(compressedArm9)
```

If the input is not BLZ-encoded, `decompress` returns it unchanged.

#### Compressing

```kotlin
val arm9Data = File("arm9.bin").readBytes()

// arm9 = true leaves the first 0x4000 bytes uncompressed,
// as required for DS ARM9 secure-area binaries.
val compressed: ByteArray = BlzCodec.compress(arm9Data, arm9 = true)

// For overlay files, use the default (arm9 = false)
val compressedOverlay: ByteArray = BlzCodec.compress(overlayData)
```

## Libraries & tools using kotlin-nds

- [pokemon-map-randomizer](https://github.com/nathanfallet/pokemon-map-randomizer): A Kotlin/Compose Multiplatform
  port of hgss-map-randomizer, the original C++ map randomizer for Pokémon HeartGold, SoulSilver, Black 2, and
  White 2.

If you are using kotlin-nds in your project/library, please let us know by opening a pull request to add it to this
list!
