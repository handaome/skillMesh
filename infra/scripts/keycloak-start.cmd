@echo off
rem Keycloak launcher wrapper.
rem
rem Why a .cmd wrapper: Git Bash rewrites arguments that look like Unix
rem paths. "--http-relative-path=/kc" becomes
rem "C:\Program Files\Git\kc" and Keycloak then fails with
rem "Specified path is an invalid URI". Keeping the arguments inside a
rem batch file avoids MSYS argument conversion entirely.
rem
rem NOTE: keep this file ASCII-only. cmd.exe reads .cmd files using the
rem OEM codepage (GBK on zh-CN Windows); UTF-8 Chinese comments decode
rem into bytes that cmd treats as metacharacters and the batch file breaks.
rem
rem Required environment variables (exported by start-keycloak.sh using
rem Windows-style paths):
rem   SKILLMESH_KC_HOME  Keycloak install dir
rem   SKILLMESH_KC_PORT  HTTP port
rem   SKILLMESH_KC_HOST  bind address

setlocal

if not defined SKILLMESH_KC_HOME (
  echo SKILLMESH_KC_HOME is not set >&2
  exit /b 1
)
if not defined SKILLMESH_KC_PORT set SKILLMESH_KC_PORT=8180
if not defined SKILLMESH_KC_HOST set SKILLMESH_KC_HOST=127.0.0.1

"%SKILLMESH_KC_HOME%\bin\kc.bat" start-dev --http-port=%SKILLMESH_KC_PORT% --http-host=%SKILLMESH_KC_HOST% --http-relative-path=/kc --import-realm
