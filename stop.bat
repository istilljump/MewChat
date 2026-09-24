@echo off
rem ============================================================================
rem  MewChat 停止脚本（Windows）
rem
rem  用法：
rem    stop.bat             停掉应用（8080）与本地假模型端点（18124）
rem    stop.bat app         只停应用
rem    stop.bat fake        只停假模型端点
rem
rem  为什么需要它：应用运行时 Windows 会锁住 target 下的 jar，
rem  此时执行 mvn clean / package 会报"无法删除 jar"，看起来像构建坏了。
rem  先跑本脚本再构建。
rem
rem  MySQL 不在本脚本范围内：它是你自己的数据库服务，停不停由你决定。
rem
rem  [!] 本文件是 GBK 编码（与项目其它 Windows 脚本一致）。
rem ============================================================================

setlocal enabledelayedexpansion
chcp 936 >nul 2>&1
cd /d "%~dp0"
title MewChat 停止

set "MODE=%~1"
if "%MODE%"=="" set "MODE=all"

echo.
echo ============ MewChat 停止 ============
echo.

if /i "%MODE%"=="all"  call :kill_port 8080 "应用"
if /i "%MODE%"=="app"  call :kill_port 8080 "应用"
if /i "%MODE%"=="all"  call :kill_port 18124 "假模型端点"
if /i "%MODE%"=="fake" call :kill_port 18124 "假模型端点"

echo.
echo 提示：MySQL 未被动过，如需停止请自行执行 net stop MySQL（需管理员）
echo       或关掉那个最小化的 mysqld 窗口。
echo.
echo 按任意键关闭窗口。
pause >nul
endlocal
exit /b 0

rem ============================== 辅助 ==============================

:kill_port
rem %1=端口 %2=名称
set "FOUND="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%~1 " ^| findstr "LISTENING" 2^>nul') do (
    if not "%%p"=="0" (
        taskkill /PID %%p /F >nul 2>&1
        if not errorlevel 1 (
            echo   [OK] 已停止 %~2（PID %%p，端口 %~1）
            set "FOUND=1"
        )
    )
)
if not defined FOUND echo   [--] %~2 未在运行（端口 %~1 无监听）
goto :eof
