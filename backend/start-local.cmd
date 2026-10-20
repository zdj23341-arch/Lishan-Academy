@echo off
setlocal
cd /d "%~dp0"
if not exist "application-local.properties" (
    echo Missing application-local.properties. Copy application-local.properties.example and fill the credentials.
    exit /b 1
)
rem The local Spring profile owns identity mode, including DEV fixture availability.
set "IDENTITY_PROVIDER_MODE="
python -X utf8 scripts/maven.py -- spring-boot:run -Dspring-boot.run.profiles=dev,local
exit /b %errorlevel%
