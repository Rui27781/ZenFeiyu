#include "early_inject.h"

#include "loader.h"
#include "loader_settings.h"
#include "manual_map.h"

#include <stdio.h>

#include <string>
#include <vector>

namespace loader {

namespace {

std::wstring format_error(const wchar_t* what, unsigned long code) {
    wchar_t buf[256];
    _snwprintf_s(buf, _TRUNCATE, L"%ls (error %lu)", what, code);
    return std::wstring(buf);
}

// Full image path of the instance we are about to relaunch. Kept separate from
// the command line because CreateProcessW wants them as two arguments.
std::wstring image_path_of(DWORD pid) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ,
                                 FALSE, pid);
    if (!process) {
        return std::wstring();
    }
    wchar_t buf[MAX_PATH * 2] = {0};
    DWORD size = (DWORD)(sizeof buf / sizeof buf[0]);
    std::wstring out;
    if (QueryFullProcessImageNameW(process, 0, buf, &size)) {
        out.assign(buf, size);
    }
    CloseHandle(process);
    return out;
}

// Environment blocks are a sequence of null-terminated "KEY=VALUE" strings
// ended by an extra null. Upsert ZENFEIYU_HWID (and surfaces if present in the
// loader process) so the injected DLL sees the user's toggle even though we
// otherwise clone the *game* environment.
void upsert_hwid_env(std::vector<wchar_t>& env) {
    const bool on = loader_settings::is_hwid_spoof_enabled();
    const std::wstring kHwid = L"ZENFEIYU_HWID";
    const std::wstring vHwid = on ? L"1" : L"0";

    // Also forward surfaces if the loader process has it set.
    wchar_t surfBuf[256] = {0};
    DWORD surfLen = GetEnvironmentVariableW(L"ZENFEIYU_HWID_SURFACES", surfBuf, 256);
    const std::wstring kSurf = L"ZENFEIYU_HWID_SURFACES";
    const std::wstring vSurf = (surfLen > 0 && surfLen < 256) ? std::wstring(surfBuf) : L"wmi";

    auto upsert = [&](const std::wstring& key, const std::wstring& value) {
        // Drop any existing entry for this key.
        std::vector<wchar_t> cleaned;
        cleaned.reserve(env.size() + key.size() + value.size() + 8);
        size_t i = 0;
        while (i < env.size()) {
            if (env[i] == L'\0') { ++i; continue; }
            size_t start = i;
            while (i < env.size() && env[i] != L'\0') ++i;
            std::wstring entry(env.data() + start, i - start);
            ++i; // skip the null
            // Compare key part (before '=')
            size_t eq = entry.find(L'=');
            std::wstring ek = (eq == std::wstring::npos) ? entry : entry.substr(0, eq);
            if (_wcsicmp(ek.c_str(), key.c_str()) == 0) {
                continue; // drop old
            }
            cleaned.insert(cleaned.end(), entry.begin(), entry.end());
            cleaned.push_back(L'\0');
        }
        // Append new
        std::wstring neu = key + L"=" + value;
        cleaned.insert(cleaned.end(), neu.begin(), neu.end());
        cleaned.push_back(L'\0');
        // Ensure double-null terminator
        if (cleaned.empty() || cleaned.back() != L'\0') cleaned.push_back(L'\0');
        if (cleaned.size() < 2 || cleaned[cleaned.size() - 2] != L'\0')
            cleaned.push_back(L'\0');
        env.swap(cleaned);
    };

    if (env.empty()) {
        // No game env available — build a minimal block from the loader's.
        // CreateProcess with nullptr env would inherit the loader process, which
        // already has ZENFEIYU_HWID set by SettingsPage; nothing to do.
        return;
    }

    upsert(kHwid, vHwid);
    upsert(kSurf, vSurf);
}

} // namespace

std::wstring inject_early(DWORD existing_pid, const std::wstring& command_line,
                          DWORD& out_new_pid) {
    if (existing_pid == 0 || command_line.empty()) {
        return L"Native Bypass: no instance command line to relaunch from";
    }

    std::wstring image = image_path_of(existing_pid);
    if (image.empty()) {
        return format_error(L"Native Bypass: QueryFullProcessImageName", GetLastError());
    }

    // The game's working directory is part of how it finds its own files; read
    // it off the running instance rather than guessing next to the exe. PROCESS_VM_READ
    // is required for the PEB read - with only QUERY_LIMITED_INFORMATION this
    // silently came back empty and the child inherited the LOADER's directory,
    // which is fatal for launchers that pass a relative --gameDir.
    std::wstring cwd;
    std::vector<wchar_t> env;
    {
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ,
                                     FALSE, existing_pid);
        if (process) {
            cwd = read_working_directory(process);
            env = read_environment_block(process);
            CloseHandle(process);
        }
    }
    if (cwd.empty()) {
        // Relaunching without it gives a game sitting in the wrong directory: it
        // starts and then dies. Keep the instance the user has instead.
        return L"Native Bypass: could not read the instance working directory "
               L"(process may be protected); not relaunching into a wrong one";
    }

    // Make sure the HWID toggle is visible to the new process / DLL.
    upsert_hwid_env(env);

    const void* dll_data = nullptr;
    size_t dll_size = 0;
    if (!get_embedded_dll(dll_data, dll_size)) {
        return L"Embedded ZenFeiyu.dll resource not found in loader EXE";
    }

    // CreateProcessW may write into the command line buffer, so hand it a copy.
    std::vector<wchar_t> cmd(command_line.begin(), command_line.end());
    cmd.push_back(L'\0');

    STARTUPINFOW si{};
    si.cb = sizeof si;
    PROCESS_INFORMATION pi{};

    // Hand over the instance's own environment block (with HWID vars upserted)
    // so the relaunch is a clone of what the launcher started. Falls back to
    // inheriting the loader's environment when the block was not readable.
    if (!CreateProcessW(image.c_str(), cmd.data(), nullptr, nullptr, FALSE,
                        CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT,
                        env.empty() ? nullptr : env.data(),
                        cwd.c_str(), &si, &pi)) {
        return format_error(L"Native Bypass: CreateProcess(CREATE_SUSPENDED)",
                            GetLastError());
    }

    // The child is frozen: no user code of its own has run, so the DLL gets a
    // clean process to set up in. Manual map keeps us away from the loader lock.
    std::wstring err = inject_in_memory(pi.dwProcessId, dll_data, dll_size);
    if (!err.empty()) {
        // Never resume a half-initialised process, and leave the instance the
        // user already had running untouched.
        TerminateProcess(pi.hProcess, 1);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        out_new_pid = 0;
        return L"Native Bypass: inject into suspended process failed: " + err;
    }

    // Injection is in. Now it is safe to retire the old instance (overlapping
    // logins are worse than a moment of downtime) and let the new one go.
    HANDLE old_process = OpenProcess(PROCESS_TERMINATE, FALSE, existing_pid);
    if (old_process) {
        TerminateProcess(old_process, 0);
        CloseHandle(old_process);
    }

    if (ResumeThread(pi.hThread) == (DWORD)-1) {
        // The DLL is mapped but the process is stuck suspended: still better to
        // hand back the pid so the caller can report what happened.
        DWORD code = GetLastError();
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        out_new_pid = pi.dwProcessId;
        return format_error(L"Native Bypass: ResumeThread", code);
    }

    out_new_pid = pi.dwProcessId;
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return std::wstring();
}

} // namespace loader
