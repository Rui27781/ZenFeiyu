#include "hwid_config.h"

#include <cstdlib>
#include <cstring>
#include <string>
#include <windows.h>

namespace hwid {

namespace {

// Defaults chosen for safety / detection resistance:
//   enabled  = true
//   surfaces = "wmi"   (vtable only — no executable-byte patches)
//
// Optional environment overrides (checked once at first config()):
//   ZENFEIYU_HWID=0|1|true|false     force enable/disable
//   ZENFEIYU_HWID_SURFACES=wmi,net   comma-separated surface list
//   ZENFEIYU_HWID_SEED=<ull>         identity seed (0 = derive from hardware)
//
// The loader can set these on itself; Native Bypass / Early Inject children
// inherit them. Normal inject into an already-running process cannot change
// the target's environment, so the DLL falls back to these defaults.

constexpr bool kDefaultEnabled = true;
constexpr unsigned long long kDefaultSeed = 0;

std::wstring env_or_empty(const wchar_t* name) {
    DWORD n = GetEnvironmentVariableW(name, nullptr, 0);
    if (n == 0) return {};
    std::wstring buf(n, L'\0');
    DWORD got = GetEnvironmentVariableW(name, buf.data(), n);
    if (got == 0 || got >= n) return {};
    buf.resize(got);
    return buf;
}

bool parse_bool(const std::wstring& s, bool fallback) {
    if (s.empty()) return fallback;
    if (s == L"0" || s == L"false" || s == L"FALSE" || s == L"off" || s == L"OFF")
        return false;
    if (s == L"1" || s == L"true" || s == L"TRUE" || s == L"on" || s == L"ON")
        return true;
    return fallback;
}

unsigned long long parse_ull(const std::wstring& s, unsigned long long fallback) {
    if (s.empty()) return fallback;
    try {
        size_t idx = 0;
        unsigned long long v = std::stoull(s, &idx, 0);
        if (idx == s.size()) return v;
    } catch (...) {}
    return fallback;
}

Config make_config() {
    Config c;
    c.enabled = parse_bool(env_or_empty(L"ZENFEIYU_HWID"), kDefaultEnabled);

    std::wstring surfaces = env_or_empty(L"ZENFEIYU_HWID_SURFACES");
    if (surfaces.empty()) {
        // wmi only — no code patches, hardest to detect.
        c.surfaces = L"wmi";
    } else {
        c.surfaces = surfaces;
    }

    c.force_get_hook = false;
    c.verbose = false;
    c.seed = parse_ull(env_or_empty(L"ZENFEIYU_HWID_SEED"), kDefaultSeed);
    return c;
}

} // namespace

const Config& config() {
    static const Config c = make_config();
    return c;
}

// Kept for the log line's format string. There is no file any more.
const std::wstring& config_path() {
    static const std::wstring kNone;
    return kNone;
}

bool surface_on(const char* name) {
    const Config& c = config();
    if (!c.enabled) return false;
    if (!name) return false;

    const std::wstring& list = c.surfaces;
    if (list == L"all") return true;
    if (list == L"none") return false;

    const size_t n = std::strlen(name);
    size_t i = 0;
    while (i < list.size()) {
        while (i < list.size() && (list[i] == L' ' || list[i] == L',')) ++i;
        const size_t start = i;
        while (i < list.size() && list[i] != L',' && list[i] != L' ') ++i;
        const size_t len = i - start;
        if (len == n) {
            bool same = true;
            for (size_t k = 0; k < n; ++k) {
                const wchar_t a = static_cast<wchar_t>(
                    static_cast<unsigned char>(name[k]));
                wchar_t b = list[start + k];
                if (b >= L'A' && b <= L'Z') b = static_cast<wchar_t>(b - L'A' + L'a');
                wchar_t al = a;
                if (al >= L'A' && al <= L'Z') al = static_cast<wchar_t>(al - L'A' + L'a');
                if (al != b) { same = false; break; }
            }
            if (same) return true;
        }
    }
    return false;
}

} // namespace hwid
