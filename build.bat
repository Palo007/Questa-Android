@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem ==========================================================================
rem  Questa build helper. Double-click it, or run: build.bat [dev|release|both|installdev|forget] [install] [nokey]
rem  - nokey   : leave the Dropbox app key seed out of the APK (the user types the key in Settings)
rem  - dev     : debug build, app "Questa dev" (io.github.palo007.questa.dev), DEV icon
rem  - release : signed release build, app "Questa" (io.github.palo007.questa)
rem  The APKs are copied to the out\ folder next to this file.
rem ==========================================================================
cd /d "%~dp0"
rem Started from PowerShell 7, the inherited PSModulePath stops Windows PowerShell
rem from loading its own modules (Read-Host, ConvertTo-SecureString). Clear it here.
set "PSModulePath="

rem --- Java: prefer the Android Studio JDK (Java 21+), else JAVA_HOME ----------
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined JAVA_HOME (
  echo [ERROR] No Java found. Install JDK 21 or Android Studio, then try again.
  goto :fail
)

set "CHOICE=%~1"
rem Version (appVersionName in gradle.properties, same as app/build.gradle.kts) and build time.
set "APPVER=0"
for /f "tokens=2 delims==" %%V in ('findstr /b /c:"appVersionName=" "%~dp0gradle.properties" 2^>nul') do set "APPVER=%%V"
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"`) do set "STAMP=%%T"
echo Version v%APPVER%   Build time %STAMP%
if not "%CHOICE%"=="" goto :pick
echo.
echo   Questa build
echo   ------------
echo   1  Dev app      (Questa dev)
echo   2  Release app  (Questa)
echo   3  Both
echo   4  Forget the saved release password
echo.
set "N="
set /p "N=Pick 1-4: "
if "%N%"=="1" set "CHOICE=dev"
if "%N%"=="2" set "CHOICE=release"
if "%N%"=="3" set "CHOICE=both"
if "%N%"=="4" set "CHOICE=forget"
:pick
set "INSTALL="
set "BUILT_DEV="
set "BUILT_REL="
if /i "%CHOICE%"=="forget" goto :forget
if /i "%CHOICE%"=="installdev" (
  set "CHOICE=dev"
  set "INSTALL=1"
)
if /i "%CHOICE%"=="dev"     goto :askinstall
if /i "%CHOICE%"=="release" goto :askinstall
if /i "%CHOICE%"=="both"    goto :askinstall
echo [ERROR] Unknown choice "%CHOICE%". Use dev, release, both, installdev or forget.
goto :fail

:askinstall
rem "nokey" anywhere after the choice leaves the Dropbox key seed out of the APK.
set "KEYARG="
if /i "%~2"=="nokey" set "KEYARG=-PnoKeySeed"
if /i "%~3"=="nokey" set "KEYARG=-PnoKeySeed"
rem Command line: install only with "installdev" or a 2nd argument "install".
rem Double-click: ask.
if /i "%~2"=="install" set "INSTALL=1"
if not "%~1"=="" goto :startbuild
set "YN="
set /p "YN=Install on phone after build? (Y/n): "
if /i not "%YN%"=="n" set "INSTALL=1"
set "YK="
set /p "YK=Put the Dropbox app key from local.properties into the APK? (Y/n): "
if /i "%YK%"=="n" set "KEYARG=-PnoKeySeed"
:startbuild
if defined KEYARG (echo Dropbox key seed: left out) else (echo Dropbox key seed: from local.properties, if set)
if /i "%CHOICE%"=="release" goto :release
goto :dev

rem --- Dev ------------------------------------------------------------------
:dev
echo.
echo === Building the DEV app ===
call "%~dp0gradlew.bat" :app:assembleDebug %KEYARG%
if errorlevel 1 goto :fail
if not exist out mkdir out
copy /y "app\build\outputs\apk\debug\app-debug.apk" "out\Questa-dev.apk" >nul
copy /y "app\build\outputs\apk\debug\app-debug.apk" "out\Questa-dev-v%APPVER%.apk" >nul
echo [OK] out\Questa-dev.apk
echo [OK] out\Questa-dev-v%APPVER%.apk   (version v%APPVER%-dev)
set "BUILT_DEV=%~dp0out\Questa-dev.apk"
if /i "%CHOICE%"=="both" goto :release
goto :install

rem --- Install on the phone (tools\install-phone.ps1) -------------------------
rem Wi-Fi adb first, then the saved IP (out\.adb_wifi), then USB. See README.md.
:install
if not defined INSTALL goto :done
rem The script reads the APK paths from BUILT_DEV / BUILT_REL (empty = not built).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\install-phone.ps1"
if errorlevel 1 (
  echo [WARN] The build worked, but the install on the phone did not.
  goto :fail
)
goto :done

rem --- Release --------------------------------------------------------------
:release
echo.
echo === Building the RELEASE app ===
if not defined KEYSTORE_PATH set "KEYSTORE_PATH=%~dp0my-upload-key.jks"
rem The password is kept in signing.pwd, encrypted with Windows DPAPI: only this
rem Windows user on this PC can read it back. Git ignores the file.
rem Delayed expansion is off, so "!" and "^" in the password are kept.
set "PWDFILE=%~dp0signing.pwd"
set "PWD_SAVED="
set "NEWKEY="
if not exist "%KEYSTORE_PATH%" set "NEWKEY=1"
if defined STORE_PASSWORD goto :havepass
if defined NEWKEY goto :askpass
if not exist "%PWDFILE%" goto :askpass
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$s=Get-Content -LiteralPath $env:PWDFILE | ConvertTo-SecureString; [Net.NetworkCredential]::new('',$s).Password"`) do set "STORE_PASSWORD=%%P"
if not defined STORE_PASSWORD (
  echo [WARN] Could not read the saved password. Type it again.
  goto :askpass
)
set "PWD_SAVED=1"
goto :havepass

:askpass
if not defined NEWKEY goto :askold
echo No signing key found at:
echo   %KEYSTORE_PATH%
echo The release app must be signed. You can make a new key now.
echo KEEP THE KEY FILE SAFE. Updates of the installed app need the SAME key.
set "MK="
set /p "MK=Make a new key? (y/n): "
if /i not "%MK%"=="y" goto :fail
rem New key: type the password twice, at least 6 characters.
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$a=[Net.NetworkCredential]::new('',(Read-Host 'New key password (6+ characters)' -AsSecureString)).Password; $b=[Net.NetworkCredential]::new('',(Read-Host 'Type it again' -AsSecureString)).Password; if($a -ne $b){[Console]::Error.WriteLine('The two passwords are not the same.')} elseif($a.Length -lt 6){[Console]::Error.WriteLine('The password is too short.')} else {$a}"`) do set "STORE_PASSWORD=%%P"
if not defined STORE_PASSWORD goto :fail
goto :havepass
:askold
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "[Net.NetworkCredential]::new('',(Read-Host 'Key password' -AsSecureString)).Password"`) do set "STORE_PASSWORD=%%P"
if not defined STORE_PASSWORD (
  echo [ERROR] The password is empty.
  goto :fail
)

:havepass
if not defined KEY_PASSWORD set "KEY_PASSWORD=%STORE_PASSWORD%"
if not defined NEWKEY goto :build_release
"%JAVA_HOME%\bin\keytool.exe" -genkeypair -keystore "%KEYSTORE_PATH%" -alias upload -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Questa" -storepass:env STORE_PASSWORD -keypass:env KEY_PASSWORD
if errorlevel 1 goto :fail
echo [OK] New key made: %KEYSTORE_PATH%
echo      Make a backup copy of it now (for example on a USB stick or in a password manager).
:build_release
call "%~dp0gradlew.bat" :app:assembleRelease %KEYARG%
if errorlevel 1 (
  if defined PWD_SAVED echo If the saved password is wrong, run "build.bat forget" and try again.
  goto :fail
)
rem Save the password only after it worked, so a wrong one is never saved.
if defined PWD_SAVED goto :copy_release
powershell -NoProfile -Command "ConvertTo-SecureString $env:STORE_PASSWORD -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $env:PWDFILE"
if not errorlevel 1 echo [OK] Password saved ^(encrypted^). The next release build will not ask for it.
:copy_release
if not exist out mkdir out
copy /y "app\build\outputs\apk\release\app-release.apk" "out\Questa.apk" >nul
copy /y "app\build\outputs\apk\release\app-release.apk" "out\Questa-v%APPVER%.apk" >nul
echo [OK] out\Questa.apk
echo [OK] out\Questa-v%APPVER%.apk   (version v%APPVER%)
set "BUILT_REL=%~dp0out\Questa.apk"
goto :install

:forget
if exist "%~dp0signing.pwd" del "%~dp0signing.pwd"
echo [OK] The saved password is removed. The next release build asks for it again.
goto :done

:done
echo.
echo Done. The APKs are in: %~dp0out
endlocal
if "%~1"=="" pause
exit /b 0

:fail
echo.
echo [FAILED] See the messages above.
endlocal
if "%~1"=="" pause
exit /b 1
