# AeroScape RSPS — Complete Build Guide

_How to build a working OSRS private server client from a RuneLite fork. Documented 2026-03-16 after a 16-hour marathon from v0.1 to v0.26._

---

## Architecture Overview

```
┌─────────────────────┐       ┌──────────────────────┐
│   AeroScape Client  │       │   AeroScape Server    │
│   (RuneLite fork)   │◄─────►│   (C# .NET 8)        │
│                     │       │                       │
│  - Patched gamepack │  TCP  │  - JS5 cache service  │
│  - RSA key override │  :443 │  - Login handler      │
│  - Host validation  │ :43594│  - Game world (TODO)  │
│  - AeroScape config │       │                       │
└─────────────────────┘       └──────────────────────┘
```

**Client repo:** Aero-VI/aeroscape-client (RuneLite fork)  
**Server repo:** Aero-VI/aeroscape-server (C# .NET 8)  
**DNS:** play.aeroverra.com → 51.79.134.185 (Cloudflare, DNS only, NOT proxied)  
**Cache source:** OpenRS2 archive (https://archive.openrs2.org/)

---

## Step-by-Step: How We Built It

### Phase 1: Client Foundation

#### 1.1 Fork RuneLite
```bash
git clone https://github.com/runelite/runelite.git aeroscape-client
cd aeroscape-client
```

RuneLite switched from Maven to Gradle. Build with:
```bash
./gradlew :client:shadowJar  # or :runelite-client:shadowJar
```
Output: `client/build/libs/client-*-shaded.jar` or `runelite-client/build/libs/client-*-shaded.jar`

#### 1.2 Embedded jav_config.ws
Create `runelite-client/src/main/resources/net/runelite/client/rs/aeroscape_jav_config.ws`:
```
title=AeroScape
codebase=http://play.aeroverra.com/
cachedir=aeroscape
storebase=0
initial_jar=aeroscape-client.jar
initial_class=client.class
viewerversion=124
win_sub_version=1
mac_sub_version=2
other_sub_version=2
window_preferredwidth=800
window_preferredheight=600
applet_minwidth=765
applet_minheight=503
applet_maxwidth=5760
applet_maxheight=2160
param=25=236
param=9=ElZAIrq5NpKN6D3mDdihco3oPeYN2KFy2DCquj7JMmECPmLrDP3Bnw
param=3=false
param=12=1
param=18=
param=10=0
param=21=0
param=8=true
param=15=0
param=13=play.aeroverra.com
param=6=0
param=4=1
param=7=0
param=17=http://play.aeroverra.com/worldlist.ws
param=14=0
param=5=0
param=16=false
```

**Critical params:**
- `param=13` = game server hostname (MUST be a domain, not raw IP — gamepack validates hostname)
- `param=25` = revision number (must match cache and gamepack)
- `param=7` = GameBuild (0 = no build check)
- `param=12` = world number
- `cachedir` = local cache directory name (use unique name to avoid conflicts with real OSRS)

#### 1.3 ClientConfigLoader.java — Config Fallback
Modify `runelite-client/src/main/java/net/runelite/client/rs/ClientConfigLoader.java`:

1. Add `ensureAeroscapeDefaults()` method that sets all critical params via `putIfAbsent()`
2. Add `fetchEmbedded()` fallback that loads from `aeroscape_jav_config.ws` resource
3. In `fetch()`, call `ensureAeroscapeDefaults(config)` after loading

This ensures params are always present even if the server's jav_config.ws is unavailable.

#### 1.4 ClientLoader.java — Embedded Config Fallback Chain
Add fallback in `downloadConfig()`: if all server URLs fail, call `fetchEmbedded()`.

#### 1.5 RSAppletStub.java — getCodeBase Override
Override `getCodeBase()` to return `http://play.aeroverra.com/`. This controls the hostname the gamepack uses for host validation.

---

### Phase 2: Host Validation Bypass

The OSRS gamepack validates that the server hostname ends with `.runescape.com` or `.jagex.com`. This check exists in multiple obfuscated classes and **changes location between revisions**.

#### How to find the host validation classes:
```bash
# Search ALL classes in the shaded JAR for "runescape.com"
for cls in $(unzip -l client.jar | grep '\.class$' | awk '{print $4}'); do
    result=$(unzip -p client.jar "$cls" | strings | grep -c "runescape\.com")
    if [ "$result" -gt 0 ]; then
        echo "$cls: $result matches"
    fi
done
```

#### Rev 235 locations:
- `bm.class` — `.runescape.com` and `aeroverra.com` checks
- `client.class` — `runescape.com` check

#### Rev 236 locations (obfuscation changed!):
- `bv.class` — `.runescape.com` and `.aeroverra.com` (2 occurrences)
- `ry.class` — `runescape.com` (1 occurrence)
- `client.class` — `runescape.com` (1 occurrence)

#### How to patch (binary replacement, same length):
```python
import zipfile, subprocess

for cls in ["bv.class", "ry.class", "client.class"]:
    with zipfile.ZipFile("client.jar") as z:
        data = z.read(cls)
    patched = data.replace(b"runescape.com", b"aeroverra.com")
    if patched != data:
        with open(cls, "wb") as f:
            f.write(patched)
        subprocess.run(["jar", "uf", "client.jar", cls])
```

**IMPORTANT:** `runescape.com` and `aeroverra.com` are both 13 characters. Same-length replacement preserves class file structure. If your domain is a different length, this won't work — you'd need to edit the constant pool length prefix.

**IMPORTANT:** These classes come from the `injected-client` Gradle dependency, not your source code. Post-build patching is required after every build.

---

### Phase 3: RSA Key Override

The OSRS gamepack encrypts the login block using RSA with a hardcoded public key. The server needs the matching private key to decrypt.

#### The Problem
The gamepack has **multiple RSA key locations** in different obfuscated classes:

**Rev 236:**
- `bb.class` — fields `ab` (exponent=65537) and `av` (modulus, 512-bit)
- `cq.class` — fields `ac` (exponent=0x10001=65537) and `ax` (modulus, 1024-bit, hex-encoded)

**The actual login RSA uses `cq.ax`**, not `bb.av`! We spent hours patching the wrong class.

#### How to find RSA key classes:
```bash
# Find classes with BigInteger references
for cls in $(unzip -l client.jar | grep '^[a-z][a-z]\.class$'); do
    result=$(unzip -p client.jar "$cls" | strings | grep -c "BigInteger")
    if [ "$result" -gt 0 ]; then
        echo "$cls: $result BigInteger refs"
    fi
done

# Check each for long number strings (modulus)
for cls in bb.class cq.class; do
    javap -v -classpath client.jar $(basename $cls .class) | grep -E "Utf8.*[0-9]{30,}"
done
```

#### Why Binary Patching Doesn't Work
The gamepack classes come from the `injected-client` dependency. Our patched `.class` files get overwritten by the Gradle shadow JAR assembly. Even if we patch after build, Java 17+ makes `static final` BigInteger fields effectively immutable via normal reflection.

#### The Solution: Runtime Override via Unsafe
In `ClientLoader.java`, AFTER `loadClient()` loads the gamepack classes:

```java
// Get Unsafe instance
Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
unsafeField.setAccessible(true);
sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

// Override RSA modulus in ALL gamepack classes
for (String className : new String[]{"bb", "cq"}) {
    Class<?> targetClass = ClassLoader.loadClass(className);
    for (Field f : targetClass.getDeclaredFields()) {
        if (f.getType() == BigInteger.class && Modifier.isStatic(f.getModifiers())) {
            f.setAccessible(true);
            BigInteger current = (BigInteger) f.get(null);
            if (current != null && current.bitLength() > 256
                && !current.equals(BigInteger.valueOf(65537))) {
                // This is a modulus — override it
                long offset = unsafe.staticFieldOffset(f);
                Object base = unsafe.staticFieldBase(f);
                unsafe.putObject(base, offset, YOUR_MODULUS);
            }
        }
    }
}
```

**Key insight:** The override must target ALL classes with BigInteger modulus fields, not just one. Check every revision for new locations.

#### Generating the Server RSA Key
In `RsaKeys.cs`:
```csharp
var rsa = RSA.Create(1024); // MUST be 1024-bit to match gamepack block size
```

The server uses raw BigInteger modPow (no OAEP/PKCS1 padding) — this matches how OSRS does RSA.

---

### Phase 4: JS5 Cache Server

The JS5 (Jagex Store 5) protocol serves game cache data to the client.

#### Cache Files
Download from OpenRS2 archive: https://archive.openrs2.org/
Match the revision to your gamepack (rev 236 for our build).

Files needed:
- `main_file_cache.dat2` — sector data (520 bytes per sector)
- `main_file_cache.idx0` through `idx24` — index files (6 bytes per entry: size:3 + sector:3)
- `main_file_cache.idx255` — meta-index
- `master_index.dat` — pre-built master checksum table

#### JS5 Protocol

**Handshake:**
```
Client → Server: [opcode=15][revision:4 bytes BE][xtea_key0:4][xtea_key1:4][xtea_key2:4][xtea_key3:4]
                  Total: 21 bytes (1 opcode consumed by main handler + 20 read by JS5)
Server → Client: [0x00] (OK) or [0x06] (outdated)
```

**CRITICAL:** The handshake is 21 bytes total, not 5! The 16 XTEA key bytes after the revision MUST be consumed or they'll be misinterpreted as cache requests.

**Request loop — message types:**
```
[0][index:1][archive:2] — urgent cache request (respond with data)
[1][index:1][archive:2] — prefetch cache request (respond with data)
[2][0][0][0]            — logged out notification (NO response)
[3][0][0][0]            — logged in notification (NO response)
[4][key:3] + 12 more    — encryption keys (read 12 extra bytes, NO response)
```

**Response format (3-byte header):**
```
[index:1][archive_hi:1][archive_lo:1][container_data_with_0xFF_separators]
```

Container data includes the compression byte as-is from disk. 0xFF separator bytes are inserted at wire positions 512, 1024, 1536, etc.

**CRITICAL — DO NOT modify the compression byte!** An earlier version OR'd 0x80 (prefetch flag) into the compression byte, which broke CRC validation. Serve containers exactly as read from disk.

#### Master Checksum Table (255,255)
Use the pre-built `master_index.dat` from the OpenRS2 cache download. Do NOT compute CRCs dynamically — getting them exactly right is extremely difficult.

```csharp
// For (255,255) requests:
_masterChecksumTable = File.ReadAllBytes(CachePath + "/master_index.dat");
```

#### Container Loading (LoadContainer)
Read sector chains from dat2 using index files:
```
idx_entry = idx[archive * 6 : archive * 6 + 6]
size = (entry[0] << 16) | (entry[1] << 8) | entry[2]
start_sector = (entry[3] << 16) | (entry[4] << 8) | entry[5]

For each sector:
  offset = sector * 520
  header = dat2[offset : offset + 8]  // [archiveId:2][chunk:2][nextSector:3][indexId:1]
  data = dat2[offset + 8 : offset + 520]  // 512 bytes
```

**DO NOT strip version trailers.** Serve containers exactly as read from disk.

#### Dual Port Listening
The game server must listen on BOTH port 43594 (standard) AND port 443 (for JS5, which OSRS uses because it passes through firewalls). Use two TcpListeners with `AmbientCapabilities=CAP_NET_BIND_SERVICE` in systemd to bind port 443 as non-root.

---

### Phase 5: Login Protocol (Rev 236)

#### Connection Flow
```
1. Client connects on port 43594
2. Sends service opcode: 14 (login)
3. Server sends: [0x00 status][8-byte server seed] = 9 bytes
4. Client sends login block
5. Server processes and responds
```

#### Login Block Format (Rev 236)
```
[loginType:1][payloadSize:2 BE]
PAYLOAD:
  [revision:4 BE]
  [subVersion:4 BE]
  [serverVersion:4 BE]     ← NEW in rev 236 (was missing, caused field misalignment)
  [clientType:1]
  [platformType:1]
  [extAuthType:1]
  [rsaSize:2 BE]            ← Size of the RSA-encrypted block
  [rsaBlock:rsaSize bytes]  ← RSA-encrypted with gamepack's public key
  [xteaBlock:remaining]     ← XTEA-encrypted with seeds from RSA block
```

#### RSA Block (decrypted)
```
[magic:1]     — must be 0x01 (rev 236) or 0x0A (older revisions)
[seed0:4][seed1:4][seed2:4][seed3:4]  — ISAAC/XTEA seeds
[sessionId:8]
[otpType:1]   — auth type (0=token, 1=remember, 2=none, 3=forget)
[otpData:4]   — OTP code or padding
[authType:1]  — 0=password, 2=token
[credentials] — null-terminated strings (username, password)
```

#### Login Success Response (Rev 236, 36 bytes)
```
[responseCode:1]     = 2 (OK)
[payloadSize:1]      = 37
[authenticatorType:1] = 0
[authenticatorCode:4] = 0
[staffModLevel:1]    = rights
[playerMod:1]        = 1 if rights >= 2
[playerIndex:2 BE]   = dynamic
[memberStatus:1]     = 1
[accountHash:8]      = 0
[userId:8]           = 0
[userHash:8]         = 0
```

---

### Phase 6: Build & Release Process

```bash
# 1. Build
cd aeroscape-client
./gradlew :client:shadowJar

# 2. Post-build: patch host validation
python3 patch_hosts.py  # replaces runescape.com → aeroverra.com in bv, ry, client classes

# 3. Fix manifest (Gradle sometimes drops Main-Class)
echo "Main-Class: net.runelite.client.RuneLite" > MANIFEST.MF
jar ufm client.jar MANIFEST.MF

# 4. Verify
javap -v -classpath client.jar bb | grep "Utf8.*[0-9]{50}"  # Check RSA modulus
strings client.jar | grep "runescape.com" | wc -l            # Should be minimal (only URL strings)
unzip -p client.jar META-INF/MANIFEST.MF | grep Main-Class   # Verify manifest

# 5. Release
# Upload to GitHub as vX.XX-aeroscape
```

---

## Lessons Learned

1. **The `injected-client` dependency overwrites gamepack classes.** Any binary patches to gamepack `.class` files get replaced during Gradle shadow JAR assembly. Use runtime override (Unsafe) instead.

2. **RSA keys can be in MULTIPLE classes.** Don't assume there's only one. Search ALL two-letter classes for BigInteger fields > 256 bits.

3. **The JS5 handshake is 21 bytes, not 5.** 4 bytes revision + 16 bytes XTEA keys. Not consuming the XTEA bytes causes protocol desync (fake "cache requests" from the leftover bytes).

4. **Never modify the compression byte in JS5 responses.** The prefetch 0x80 flag corrupts CRC validation. Serve containers byte-for-byte as read from disk.

5. **Use `master_index.dat` from OpenRS2 directly.** Dynamic CRC computation is error-prone (version trailer handling, CRC byte ranges, etc.).

6. **Host validation moves between revisions.** When updating to a new OSRS revision, re-scan all classes for `runescape.com` strings. The obfuscated class names change.

7. **`param=13` must be a hostname, not an IP.** The gamepack validates the host string. Raw IPs fail the `endsWith(".aeroverra.com")` check.

8. **Rev 236 login protocol has extra fields.** `serverVersion` (4 bytes) was added after `subVersion`. Missing it shifts all subsequent field reads.

9. **Java 17+ blocks `Field.set()` on `static final` fields.** Use `sun.misc.Unsafe.putObject()` with `staticFieldOffset()` and `staticFieldBase()` to bypass.

10. **Always commit and tag before testing.** We lost work multiple times from uncommitted changes and builds from wrong commits. Tag every release: `v0.XX-aeroscape` on both repos.

---

## Quick Reference: New Revision Checklist

When OSRS updates to a new revision:

1. [ ] Sync upstream RuneLite: `git fetch upstream && git merge upstream/master`
2. [ ] Re-apply AeroScape patches (ClientConfigLoader, ClientLoader, RSAppletStub, embedded config)
3. [ ] Build: `./gradlew :client:shadowJar`
4. [ ] Find new host validation classes: `strings *.class | grep runescape.com`
5. [ ] Patch host validation (binary replace runescape.com → aeroverra.com)
6. [ ] Find new RSA key classes: search for BigInteger fields in two-letter classes
7. [ ] Update RSA override in ClientLoader.java if class names changed
8. [ ] Download matching cache from OpenRS2 archive
9. [ ] Update server's JS5 CachePath to new revision
10. [ ] Update `param=25` in embedded jav_config to new revision
11. [ ] Rebuild server: `dotnet build --force && systemctl restart aeroscape.service`
12. [ ] Test: launch client → cache download → login screen → login → success
13. [ ] Tag both repos: `git tag v0.XX-aeroscape && git push origin --tags`

---

_Last updated: 2026-03-16 by Azula & team. 26 versions. 16 hours. From nothing to login._
