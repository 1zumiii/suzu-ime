@echo off
rem Windows (MSVC 14.44, Ninja) build of the core: rime_llm.dll plus the test tools (suzu_bench.exe, lua54.exe).
rem usage: windows\build_core.bat <llama.cpp dir (tag b11632)> <Lua src dir with lua.c>
rem   The test interpreter needs lua.c. If you have no Lua tarball, copy tests\windows\lua.c (a minimal stand-in) next to the Lua sources.
call "E:\Dev\Visual Studio 2022\Package\VC\Auxiliary\Build\vcvars64.bat" -vcvars_ver=14.44 >nul 2>&1
set PATH=E:\Dev\Jetbrains\CLion\bin\cmake\win\x64\bin;E:\Dev\Jetbrains\CLion\bin\ninja\win\x64;%PATH%
cd /d %~dp0..
cmake -G Ninja -S core -B core\build -DCMAKE_BUILD_TYPE=Release -DSUZU_BUILD_TOOLS=ON -DSUZU_LLAMA_DIR=%~1 -DSUZU_LUA_DIR=%~2
if errorlevel 1 exit /b 1
cmake --build core\build -j 6
exit /b %errorlevel%
