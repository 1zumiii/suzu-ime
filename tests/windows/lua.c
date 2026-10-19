/* Minimal stand-in for the stock lua.c interpreter (the upstream tarball is not on this machine and
 * downloading it was not authorised). What the suzu-ime tests rely on:
 *   lua54 [-e chunk] script.lua [args...]   (-e runs a chunk first) runs the script with the global table arg (arg[0] = script, arg[1..] = args),
 *                                standard libs open, C modules loadable through package.cpath.
 *   LUA_INIT                     like the stock interpreter: "@file" runs that file, anything else is run as a chunk, before the script.
 * Windows-only additions in the os table, used by tests/windows/prelude.lua to replace the /proc and footprint probes:
 *   os.getpid(), os.rss_mb() (private bytes of this process in MB), os.nthreads() (threads of this process), os.sleep(seconds), os.now_ms() (monotonic wall clock).
 */
#include <stdio.h>
#include <stdlib.h>
#include <windows.h>
#include <psapi.h>
#include <tlhelp32.h>
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#pragma comment(lib, "psapi.lib")

static int msgh(lua_State *L) {
    const char *m = lua_tostring(L, 1);
    luaL_traceback(L, L, m ? m : "(error object is not a string)", 1);
    return 1;
}

static int os_getpid(lua_State *L) { lua_pushinteger(L, (lua_Integer)GetCurrentProcessId()); return 1; }

static int os_sleep(lua_State *L) { Sleep((DWORD)(luaL_checknumber(L, 1) * 1000.0)); return 0; }

static int os_now_ms(lua_State *L) {
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    lua_pushnumber(L, (double)c.QuadPart * 1000.0 / (double)f.QuadPart);
    return 1;
}

static int os_rss_mb(lua_State *L) {
    PROCESS_MEMORY_COUNTERS_EX pm;
    pm.cb = sizeof(pm);
    if (!GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS *)&pm, sizeof(pm))) { lua_pushnil(L); return 1; }
    lua_pushinteger(L, (lua_Integer)(pm.PrivateUsage / (1024 * 1024)));
    return 1;
}

static int os_nthreads(lua_State *L) {
    DWORD pid = GetCurrentProcessId();
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    if (snap == INVALID_HANDLE_VALUE) { lua_pushnil(L); return 1; }
    THREADENTRY32 te;
    te.dwSize = sizeof(te);
    lua_Integer n = 0;
    if (Thread32First(snap, &te)) {
        do { if (te.th32OwnerProcessID == pid) ++n; } while (Thread32Next(snap, &te));
    }
    CloseHandle(snap);
    lua_pushinteger(L, n);
    return 1;
}


/* os.mem() -> table with the numbers a resident-memory comparison needs, all in MB:
 *   private  = commit charge of the process (PrivateUsage)
 *   ws       = working set (resident pages, like Linux VmRSS)
 *   ws_priv  = private part of the working set (pages not shared with other processes, like anonymous RSS)
 *   ws_shared= ws - ws_priv (file-backed and shared pages: the mmap'd model file and system DLLs) */
static int os_mem(lua_State *L) {
    PROCESS_MEMORY_COUNTERS_EX pm;
    pm.cb = sizeof(pm);
    if (!GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS *)&pm, sizeof(pm))) { lua_pushnil(L); return 1; }
    double ws_priv_pages = 0, ws_pages = 0;
    DWORD n = 1 << 16;
    PSAPI_WORKING_SET_INFORMATION *info = NULL;
    for (int tries = 0; tries < 6; ++tries) {
        free(info);
        info = (PSAPI_WORKING_SET_INFORMATION *)malloc(sizeof(PSAPI_WORKING_SET_INFORMATION) + n * sizeof(PSAPI_WORKING_SET_BLOCK));
        if (!info) break;
        if (QueryWorkingSet(GetCurrentProcess(), info, (DWORD)(sizeof(PSAPI_WORKING_SET_INFORMATION) + n * sizeof(PSAPI_WORKING_SET_BLOCK)))) {
            for (ULONG_PTR i = 0; i < info->NumberOfEntries; ++i) { ws_pages += 1; if (!info->WorkingSetInfo[i].Shared) ws_priv_pages += 1; }
            break;
        }
        n *= 4;
    }
    free(info);
    const double MB = 1024.0 * 1024.0;
    lua_createtable(L, 0, 6);
    lua_pushnumber(L, pm.PrivateUsage / MB);            lua_setfield(L, -2, "private");
    lua_pushnumber(L, pm.WorkingSetSize / MB);          lua_setfield(L, -2, "ws");
    lua_pushnumber(L, ws_priv_pages * 4096.0 / MB);     lua_setfield(L, -2, "ws_priv");
    lua_pushnumber(L, (ws_pages - ws_priv_pages) * 4096.0 / MB); lua_setfield(L, -2, "ws_shared");
    lua_pushnumber(L, pm.PeakWorkingSetSize / MB);      lua_setfield(L, -2, "ws_peak");
    return 1;
}

static int run_init(lua_State *L) {
    const char *init = getenv("LUA_INIT");
    if (!init) return LUA_OK;
    int rc = (init[0] == '@') ? luaL_loadfile(L, init + 1) : luaL_loadstring(L, init);
    return rc == LUA_OK ? lua_pcall(L, 0, 0, 0) : rc;
}

int main(int argc, char **argv) {
    /* usage: lua54 [-e "chunk"] [script.lua [args...]]   (-e runs a chunk first, like the stock interpreter) */
    const char *chunk = NULL;
    int first = 1;
    if (argc >= 3 && argv[1][0] == '-' && argv[1][1] == 'e' && argv[1][2] == '\0') { chunk = argv[2]; first = 3; }
    if (!chunk && argc < 2) { fprintf(stderr, "usage: %s [-e chunk] script.lua [args...]\n", argv[0]); return 2; }
    lua_State *L = luaL_newstate();
    if (!L) { fprintf(stderr, "cannot create state\n"); return 1; }
    luaL_openlibs(L);
    lua_getglobal(L, "os");
    lua_pushcfunction(L, os_getpid);   lua_setfield(L, -2, "getpid");
    lua_pushcfunction(L, os_rss_mb);   lua_setfield(L, -2, "rss_mb");
    lua_pushcfunction(L, os_nthreads); lua_setfield(L, -2, "nthreads");
    lua_pushcfunction(L, os_sleep);    lua_setfield(L, -2, "sleep");
    lua_pushcfunction(L, os_now_ms);   lua_setfield(L, -2, "now_ms");
    lua_pushcfunction(L, os_mem);      lua_setfield(L, -2, "mem");
    lua_pop(L, 1);
    lua_createtable(L, argc > first ? argc - first : 0, 2);
    for (int i = first; i < argc; ++i) { lua_pushstring(L, argv[i]); lua_rawseti(L, -2, i - first); }
    lua_setglobal(L, "arg");
    if (run_init(L) != LUA_OK) { fprintf(stderr, "LUA_INIT: %s\n", lua_tostring(L, -1)); lua_close(L); return 1; }
    int base = lua_gettop(L);
    lua_pushcfunction(L, msgh);
    int rc = LUA_OK;
    if (chunk) {
        rc = luaL_loadstring(L, chunk);
        if (rc == LUA_OK) rc = lua_pcall(L, 0, 0, base + 1);
        if (rc != LUA_OK) { fprintf(stderr, "%s\n", lua_tostring(L, -1)); lua_close(L); return 1; }
    }
    if (first < argc) {
        rc = luaL_loadfile(L, argv[first]);
        if (rc == LUA_OK) {
            for (int i = first + 1; i < argc; ++i) lua_pushstring(L, argv[i]);
            rc = lua_pcall(L, argc - first - 1, LUA_MULTRET, base + 1);
        }
        if (rc != LUA_OK) { fprintf(stderr, "%s\n", lua_tostring(L, -1)); lua_close(L); return 1; }
    }
    lua_close(L);
    return 0;
}
