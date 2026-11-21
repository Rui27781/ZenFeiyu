# ZenFeiyu

**ZenFeiyu** is a Minecraft client mod based on the Zen source code, enhanced through AI-assisted reverse engineering and native DLL bypass techniques.

## Project Overview

ZenFeiyu is derived from the original Zen Minecraft client (targeting Minecraft 1.20.1 + Forge 47.4.20) and has been **AI-modified (AI二改)** to improve the reverse engineering workflow, reduce detectability, and enhance the user experience.

### Key Features

- **Native DLL Bypass**: Ported native code from [OpenNilore](https://github.com/Pickbowen/OpenNilore) to implement advanced HWID spoofing, ntdll relay hooks, IAT patching, and inline hooking techniques. These modifications allow the client to bypass various anti-cheat mechanisms at the native code level.

- **AI-Assisted Reverse Engineering**: The original obfuscated jar was deobfuscated using AI tools (Opus 4.7, Enigma MCP, and Sonnet 4.6) to restore readable Java source code, enabling reproducible Gradle builds.

- **HWID Spoofing**: Includes registry and WMI-level fingerprint spoofing with configurable surfaces (WMI, net, firmware, disk). Generated fingerprints are deterministic and consistent per process.

- **Process Guarding**: Implements ntdll-resident hook relays with watchdog threads to prevent easy detection and restoration by anti-cheat systems.

- **Loader with Embedded Resources**: The native loader embeds the zen.jar resource, with integrated injection UI and native bypass capabilities.

### Technical Details

- **Version**: Minecraft 1.20.1 + Forge 47.4.20
- **Build System**: Gradle with native CMake compilation
- **Architecture**: x64 Windows
- **Runtime**: Java JNI integration with embedded DLL resources

### Native Code Porting (来自 OpenNilore)

The following native modules have been ported from the OpenNilore project to implementZenFeiyu's bypass capabilities:

| Module | Description |
|--------|-------------|
| `nt_guard` | ntdll-resident hook relay with self-healing watchdog |
| `iat_patch` | Import Address Table patching for registry/WMI interception |
| `inline_hook` | Inline x64 hooking for function prologue modification |
| `hwid_config` | HWID configuration with ini-based surf ace selection |
| `hwid_firmware` | SMBIOS table rewriting via GetSystemFirmwareTable |
| `hwid_disk` | Disk serial and ATA pass-through interception |
| `hwid_net` | MAC address spoofing via GetAdaptersAddresses hooking |
| `hwid_wmi_vtbl` | WMI vtable hooking without IAT footprint |
| `spoof_values` | Deterministic fake fingerprint generation derived from real values |

The native code has been adapted to use `ZenFeiyu` branding throughout (resources, identifiers, and configuration files).

### Build Instructions

This project uses Gradle with CMake for native compilation:

```bash
# Setup Gradle
./gradlew dll

# Or build native components
cd native
cmake --build build --config Release
```

### License

The original obfuscated bytecode is not licensed. The deobfuscated source code, build scripts, and documentation in this repository are **for research and learning purposes only**.

Using this client on servers without authorization violates most server rules. Use at your own risk.

### Contact

For discussion and support, please join the community channels.

---

*ZenFeiyu: AI-modified and native-bypassed enhancement of the Zen Minecraft client.*

## 获取方式

- QQ群：1104492901（欢迎加入讨论、反馈与技术交流）
- Issues：欢迎开 Issues 描述问题或直接提交 Pull Request，我们将不胜感激
- 本仓库**仅供学习与研究目的发布** — 用于研究客户端侧游戏改造、ASM 字节码补丁和混淆/反混淆技术。在你不拥有的服务器上使用作弊客户端违反绝大多数服务器规则，请自行承担后果。
以上文字由ai撰写。