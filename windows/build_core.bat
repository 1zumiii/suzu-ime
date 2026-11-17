@echo off
setlocal
rem Windows (MSVC 14.44, Ninja) build of the core: rime_llm.dll plus the test tools (suzu_bench.exe, lua54.exe).
rem usage: windows\build_core.bat <llama.cpp dir (tag b11632)> <Lua src dir with lua.c>
rem   The test interpreter needs lua.c. If you have no Lua tarball, copy tests\windows\lua.c (a minimal stand-in) next to the Lua sources.
rem CMake and Ninja must be on PATH. SUZU_VCVARS64 overrides Visual Studio discovery.
if not "%~1"=="" set "SUZU_LLAMA_DIR=%~f1"
if not "%~2"=="" set "SUZU_LUA_DIR=%~f2"
if not defined SUZU_LLAMA_DIR goto :usage
if not defined SUZU_LUA_DIR goto :usage
for %%I in ("%SUZU_LLAMA_DIR%") do set "SUZU_LLAMA_DIR=%%~fI"
for %%I in ("%SUZU_LUA_DIR%") do set "SUZU_LUA_DIR=%%~fI"

call :init_msvc
if errorlevel 1 exit /b %errorlevel%
where cmake.exe >nul 2>&1 || (echo CMake was not found on PATH. >&2 & exit /b 1)
where ninja.exe >nul 2>&1 || (echo Ninja was not found on PATH. >&2 & exit /b 1)

pushd "%~dp0.." || exit /b 1
cmake -G Ninja -S core -B core\build -DCMAKE_BUILD_TYPE=Release -DSUZU_BUILD_TOOLS=ON "-DSUZU_LLAMA_DIR=%SUZU_LLAMA_DIR%" "-DSUZU_LUA_DIR=%SUZU_LUA_DIR%"
if not errorlevel 1 cmake --build core\build -j 6
set "BUILD_EXIT=%errorlevel%"
popd
exit /b %BUILD_EXIT%

:init_msvc
if defined SUZU_VCVARS64 goto :load_msvc
if /i "%VSCMD_ARG_TGT_ARCH%"=="x64" exit /b 0
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq delims=" %%I in (`^""%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath^"`) do set "SUZU_VCVARS64=%%I\VC\Auxiliary\Build\vcvars64.bat"
)

:load_msvc
if not exist "%SUZU_VCVARS64%" (
    echo Visual Studio x64 build tools were not found. Set SUZU_VCVARS64 to vcvars64.bat. >&2
    exit /b 1
)
call "%SUZU_VCVARS64%" -vcvars_ver=14.44
exit /b %errorlevel%

:usage
echo Usage: windows\build_core.bat ^<llama.cpp directory^> ^<Lua source directory^> >&2
echo Alternatively, set SUZU_LLAMA_DIR and SUZU_LUA_DIR. >&2
exit /b 2
