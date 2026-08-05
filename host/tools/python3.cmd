@echo off
rem FEXCore/Source/CMakeLists.txt invokes "python3" literally (lines 148, 160, 186) to run
rem its IR and config generators. on windows the real interpreter is python.exe, and bare
rem "python3" hits the microsoft store app-execution-alias stub, which prints an install
rem prompt and exits non-zero.
rem
rem this shim is put on PATH by build.ps1 so the generators resolve, without patching FEX.
python.exe %*
