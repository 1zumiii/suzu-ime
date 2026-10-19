/* Minimal stand-in for the stock lua.c interpreter (the upstream tarball is not on this machine and
 * downloading it was not authorised). What the suzu-ime tests rely on:
 *   lua54 script.lua [args...]   runs the script with the global table arg (arg[0] = script, arg[1..] = args),
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

static int run_init(lua_State *L) {
    const char *init = getenv("LUA_INIT");
    if (!init) return LUA_OK;
    int rc = (init[0] == '@') ? luaL_loadfile(L, init + 1) : luaL_loadstring(L, init);
    return rc == LUA_OK ? lua_pcall(L, 0, 0, 0) : rc;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s script.lua [args...]\n", argv[0]); return 2; }
    lua_State *L = luaL_newstate();
    if (!L) { fprintf(stderr, "cannot create state\n"); return 1; }
    luaL_openlibs(L);
    lua_getglobal(L, "os");
    lua_pushcfunction(L, os_getpid);   lua_setfield(L, -2, "getpid");
    lua_pushcfunction(L, os_rss_mb);   lua_setfield(L, -2, "rss_mb");
    lua_pushcfunction(L, os_nthreads); lua_setfield(L, -2, "nthreads");
    lua_pushcfunction(L, os_sleep);    lua_setfield(L, -2, "sleep");
    lua_pushcfunction(L, os_now_ms);   lua_setfield(L, -2, "now_ms");
    lua_pop(L, 1);
    lua_createtable(L, argc - 2, 2);
    for (int i = 1; i < argc; ++i) { lua_pushstring(L, argv[i]); lua_rawseti(L, -2, i - 1); }
    lua_setglobal(L, "arg");
    if (run_init(L) != LUA_OK) { fprintf(stderr, "LUA_INIT: %s\n", lua_tostring(L, -1)); lua_close(L); return 1; }
    int base = lua_gettop(L);
    lua_pushcfunction(L, msgh);
    int rc = luaL_loadfile(L, argv[1]);
    if (rc == LUA_OK) {
        for (int i = 2; i < argc; ++i) lua_pushstring(L, argv[i]);
        rc = lua_pcall(L, argc - 2, LUA_MULTRET, base + 1);
    }
    if (rc != LUA_OK) { fprintf(stderr, "%s\n", lua_tostring(L, -1)); lua_close(L); return 1; }
    lua_close(L);
    return 0;
}
