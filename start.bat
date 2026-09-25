@echo off
rem ============================================================================
rem  MewChat 一键启动（Windows）
rem
rem  用法：
rem    start.bat            预检 → 按需建库/构建 → 启动应用（前台，Ctrl+C 停止）
rem    start.bat check      只做环境预检，不启动
rem    start.bat fake       顺带启动本地假模型端点（没有大模型 Key 时用它）
rem    start.bat rebuild    强制重新构建（含跑测试）后再启动
rem
rem  首次运行会生成 local.env.bat（本机配置：MySQL 路径/密码、大模型 Key、
rem  自动生成的令牌密钥）。该文件已被 .gitignore 忽略，不会进仓库。
rem
rem  [!] 本文件是 GBK 编码：应用的中文日志也是 GBK，控制台用 936 代码页才能同时
rem      正确显示脚本提示与应用日志。用 UTF-8 编辑器打开会显示乱码，属预期。
rem ============================================================================

setlocal enabledelayedexpansion
chcp 936 >nul 2>&1
cd /d "%~dp0"
title MewChat 一键启动

set "MODE=%~1"
set "APP_PORT=8080"
set "FAKE_PORT=18124"
set "JAR=target\mewchat-0.0.1-SNAPSHOT.jar"

echo.
echo ============ MewChat 一键启动 ============
echo.

rem ---------------------------------------------------------------- 1. 预检
echo [1/5] 环境预检

java -version >nul 2>&1
if errorlevel 1 (
    echo   [X] 找不到 java。请安装 JDK 17 或更高版本，并把 bin 目录加入 PATH。
    goto :fail
)
set "JAVA_VER="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined JAVA_VER set "JAVA_VER=%%v"
)
echo   [OK] Java !JAVA_VER!

if not exist "mvnw.cmd" (
    echo   [X] 当前目录下没有 mvnw.cmd，请确认在项目根目录运行本脚本。
    goto :fail
)
echo   [OK] Maven Wrapper 就绪

if not exist "local.env.bat" call :init_local_env
call "local.env.bat"

rem 三项配置必须齐全：缺任一项时，后面的 MySQL 检查会以"连不上"的形态失败，
rem 报错指向数据库连接而不是配置本身，排查方向会被带偏（阶段 16 真实踩过）
if not defined MYSQL_USER     call :env_missing MYSQL_USER
if not defined MYSQL_PASSWORD call :env_missing MYSQL_PASSWORD
if not defined DB_NAME        call :env_missing DB_NAME
if defined ENV_MISSING goto :fail

echo   [OK] 本地配置 local.env.bat（库 %DB_NAME%，账号 %MYSQL_USER%）

if /i "%MODE%"=="check" goto :check_only

rem ---------------------------------------------------------- 2. 确保 MySQL
echo.
echo [2/5] 检查 MySQL

call :port_up 3306
if defined PORT_UP (
    echo   [OK] MySQL 已在 127.0.0.1:3306 运行
) else (
    echo   [..] MySQL 未运行，尝试启动
    call :start_mysql
    call :port_up 3306
    if not defined PORT_UP (
        echo   [X] 无法自动启动 MySQL，请手动执行下面任意一种后重新运行本脚本：
        echo       1^) 管理员身份运行: net start MySQL
        echo       2^) 直接运行:      "%MYSQL_HOME%\bin\mysqld.exe" --console
        goto :fail
    )
    echo   [OK] MySQL 已启动
)

rem ---------------------------------------------------------- 3. 确保数据库
echo.
echo [3/5] 检查数据库 %DB_NAME%

set "MYSQL_EXE=%MYSQL_HOME%\bin\mysql.exe"
if not exist "%MYSQL_EXE%" (
    echo   [!] 找不到 mysql 客户端：%MYSQL_EXE%
    echo       跳过建库检查。若库还不存在，请按 README 的命令先建库。
) else (
    "%MYSQL_EXE%" -h 127.0.0.1 -P 3306 -u %MYSQL_USER% -p%MYSQL_PASSWORD% -N -e "SELECT 1" >nul 2>"%TEMP%\mewchat-mysql-err.txt"
    if errorlevel 1 (
        echo   [X] 连不上 MySQL。mysql 客户端的报错如下：
        type "%TEMP%\mewchat-mysql-err.txt" 2>nul | findstr /v /c:"Using a password"
        echo       请检查 local.env.bat 里的 MYSQL_USER / MYSQL_PASSWORD，以及 MySQL 是否已启动。
        goto :fail
    )

    rem 判断"库是否已初始化"用 表数量：结果写文件再读，不用 for /f 执行带引号的命令
    rem （cmd 会吃掉那种命令的首尾引号，实测判断会永远为"不存在"，于是对已建好的库
    rem  重跑 03/04 这类 ALTER，撞上"列已存在"直接中止）；也不能用 set /p 读，
    rem  它会把行尾的 CR 带进变量。for /f 读文件两个问题都没有。
    rem 还有一条：本段整体在 ) else ( 括号块内，块内引用变量必须写 !VAR! 延迟展开；
    rem 写成 %VAR% 取到的是"块解析那一刻"的值（空），实测会把新库误判成"已就绪"。
    set "TABLE_COUNT="
    "%MYSQL_EXE%" -h 127.0.0.1 -P 3306 -u %MYSQL_USER% -p%MYSQL_PASSWORD% -N -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='%DB_NAME%'" > "%TEMP%\mewchat-tables.txt" 2>nul
    for /f "usebackq tokens=*" %%c in ("%TEMP%\mewchat-tables.txt") do set "TABLE_COUNT=%%c"
    if "!TABLE_COUNT!"=="" set "TABLE_COUNT=0"

    if not "!TABLE_COUNT!"=="0" (
        echo   [OK] 数据库已就绪（!TABLE_COUNT! 张表）
        echo   [i]  如需升级表结构，请按 README 的顺序手动执行 sql/ 下的迁移脚本
    ) else (
        echo   [..] 数据库为空，按 01 -^> 06 顺序建库建表
        "%MYSQL_EXE%" -h 127.0.0.1 -P 3306 -u %MYSQL_USER% -p%MYSQL_PASSWORD% -e "CREATE DATABASE IF NOT EXISTS %DB_NAME% DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>nul
        for %%f in (01_schema 02_knowledge_chunk 03_pending_clarification 04_question_cluster 05_question_length 06_demo_seed) do (
            "%MYSQL_EXE%" -h 127.0.0.1 -P 3306 -u %MYSQL_USER% -p%MYSQL_PASSWORD% --default-character-set=utf8mb4 --database=%DB_NAME% < "sql\%%f.sql" 2>>"%TEMP%\mewchat-mysql-err.txt"
            if errorlevel 1 (
                echo      [X] sql\%%f.sql 执行失败，原因：
                type "%TEMP%\mewchat-mysql-err.txt" 2>nul | findstr /v /c:"Using a password"
                goto :fail
            )
        )
        echo   [OK] 建库建表完成
        echo   [i]  已写入两个演示账号：alice 与 admin，密码都是 123456
    )
)

rem ---------------------------------------------------------- 4. 按需构建
echo.
echo [4/5] 检查构建产物

set "NEED_BUILD="
if not exist "%JAR%" set "NEED_BUILD=1"
if /i "%MODE%"=="rebuild" set "NEED_BUILD=1"

if defined NEED_BUILD (
    if /i "%MODE%"=="rebuild" (
        echo   [..] 全量构建（含测试，约 1 分钟）
        call mvnw.cmd -B clean package
    ) else (
        echo   [..] 首次构建，跳过测试（要连测试一起跑可用 start.bat rebuild）
        call mvnw.cmd -B -q package -DskipTests
    )
    if errorlevel 1 (
        echo   [X] 构建失败，请查看上面的 Maven 输出
        goto :fail
    )
    echo   [OK] 构建完成
) else (
    echo   [OK] 已有 %JAR%，跳过构建（要强制重建可用 start.bat rebuild）
)

rem --------------------------------------------- 5. 可选：本地假模型端点
if /i "%MODE%"=="fake" (
    echo.
    echo [5/5] 启动本地假模型端点（无需真实 Key）

    rem 端口若被占用就先清掉：占着端口的很可能是上次残留的进程，而它可能已经处于
    rem "端口在监听、请求无响应"的状态 —— 继续用它会让应用的每次模型调用都失败
    rem （表现为所有提问都走追问），排查成本极高
    call :kill_port %FAKE_PORT%

    echo   [..] 启动中（后台运行，日志见 tools\fake-openai\fake-openai.log）
    start "" /b /d "%~dp0tools\fake-openai" run.bat %FAKE_PORT%

    rem 就绪判据是"真发一次请求能拿到 HTTP 响应"，而不是"端口在监听" ——
    rem 端口在监听却不应答（EOF）正是上面那种残留进程的典型症状
    call :wait_probe %FAKE_PORT% 20
    if defined PROBE_OK (
        echo   [OK] 假端点已就绪
    ) else (
        rem 不中断启动：假端点只是"没有 Key 也能看效果"的便利功能，
        rem 它起不来时应用照样能跑（提问会走追问），没必要时把整个启动卡住
        echo   [!] 假端点没能就绪：请求无响应
        echo       可在另一个窗口双击运行 tools\fake-openai\run.bat，再重新运行本脚本
        echo       不影响启动：只是提问会走追问（意图识别降级），不会报错
    )

    set "LLM_BASE_URL=http://127.0.0.1:%FAKE_PORT%/v1"
    set "LLM_API_KEY=local-dev-key"
    set "LLM_MODEL=fake-model"
)

rem ---------------------------------------------------------- 启动应用
echo.
call :port_up %APP_PORT%
if defined PORT_UP (
    echo   [!] 端口 %APP_PORT% 已被占用，可能已有一个实例在运行。
    echo       先执行 stop.bat 停掉它，或直接访问 http://127.0.0.1:%APP_PORT%
    goto :end_hold
)

if "%LLM_API_KEY%"=="" (
    echo   [i] 尚未配置大模型 Key：提问都会走追问（意图识别降级，不会报错）。
    echo       想看完整效果：start.bat fake   或把 Key 填进 local.env.bat 的 LLM_API_KEY
    echo.
)

echo ============ 启动中，日志如下（按 Ctrl+C 停止） ============
echo   接口地址 : http://127.0.0.1:%APP_PORT%
echo   演示账号 : alice / admin，密码都是 123456
echo   数据库   : %DB_NAME%@127.0.0.1:3306
echo   （该库名会通过 --spring.datasource.url 传给应用，与上面检查的是同一个库）
echo   （库名与账号都会传给应用，与上面检查的是同一个库、同一个账号）
if /i "%MODE%"=="fake" echo   模型     : 本地假端点 %LLM_BASE_URL%（未就绪则走追问）
echo ==========================================================
echo.

rem 账号名要做一次改名转交：本脚本的配置项叫 MYSQL_USER，而应用读的是
rem MYSQL_USERNAME（application.yml 里的 ${MYSQL_USERNAME:root}）。
rem 不转交时应用会用默认的 root，而口令又由本文件继承（两个名字恰好同名），
rem 结果就是"脚本检查 A 账号、应用连 B 账号"的静默错位 —— 用 root 连库时看不出来，
rem 一旦库账号不是 root（如只授权单库的专用账号）就变成连不上库（阶段 16 踩到）。
rem 口令不进命令行参数：进程列表里能看到参数，环境变量则不会那样暴露。
set "MYSQL_USERNAME=%MYSQL_USER%"
set "JDBC_URL=jdbc:mysql://127.0.0.1:3306/%DB_NAME%?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
java -jar "%JAR%" --spring.datasource.url="%JDBC_URL%"

echo.
echo 应用已退出。
goto :end_hold

rem ============================== 辅助 ==============================

:env_missing
rem 配置项缺失/为空时的统一出口。%~1 为缺的配置项名。
echo   [X] local.env.bat 里的 %~1 是空的。
echo       常见原因：①该项确实没填；②文件换行不是 CRLF —— cmd 解析 .bat 要求 CRLF，
echo       LF-only 的文件会让 set 行被劈开、静默不执行（阶段 16 踩到过）。
echo       不确定就删掉 local.env.bat 重新运行本脚本，会自动生成一份。
set "ENV_MISSING=1"
goto :eof

:init_local_env
rem 首次运行：按探测结果生成配置，不做交互提问 —— set /p 在输入被重定向时
rem 会把换行读进变量，生成出坏配置；而"改一行配置"比"修一个坏文件"便宜得多。
echo   [..] 首次运行，生成 local.env.bat
set "DETECTED_HOME="
call :detect_mysql_home
set "PW=%MYSQL_PASSWORD%"
if "%PW%"=="" set "PW=root"
set "GEN_SECRET="
for /f "usebackq tokens=*" %%g in (`powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')" 2^>nul`) do set "GEN_SECRET=%%g"
if not defined GEN_SECRET set "GEN_SECRET=%RANDOM%%RANDOM%%RANDOM%%RANDOM%%RANDOM%"
(
    echo rem 本机配置 —— 含数据库密码，已被 .gitignore 忽略，请勿提交
    echo set "MYSQL_HOME=!DETECTED_HOME!"
    echo set "MYSQL_USER=root"
    echo set "MYSQL_PASSWORD=!PW!"
    echo set "DB_NAME=mewchat"
    echo rem 大模型：填了 LLM_API_KEY 就用真实模型；留空则提问会走追问
    echo set "LLM_API_KEY="
    echo set "LLM_BASE_URL=https://api.deepseek.com/v1"
    echo set "LLM_MODEL=deepseek-chat"
    echo rem 令牌签名密钥：本脚本自动生成的随机值；改了会让已签发令牌立即失效
    echo set "MEWCHAT_TOKEN_SECRET=!GEN_SECRET!"
) > local.env.bat
echo   [OK] 已生成 local.env.bat（MySQL 目录=%DETECTED_HOME%）
if not "%PW%"=="root" echo   [i]  已按环境变量 MYSQL_PASSWORD 写入密码
echo   [i]  若数据库密码不是 root：编辑 local.env.bat 里的 MYSQL_PASSWORD 后重新运行本脚本
echo.
goto :eof

:detect_mysql_home
rem 依次尝试：PATH 里的 mysqld、常见安装位置
for /f "usebackq tokens=*" %%p in (`where mysqld 2^>nul`) do (
    if not defined DETECTED_HOME (
        set "P=%%p"
        set "DETECTED_HOME=!P:\bin\mysqld.exe=!"
    )
)
if not defined DETECTED_HOME if exist "E:\mysql\mysql-8.0.34-winx64\bin\mysqld.exe" set "DETECTED_HOME=E:\mysql\mysql-8.0.34-winx64"
if not defined DETECTED_HOME if exist "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe" set "DETECTED_HOME=C:\Program Files\MySQL\MySQL Server 8.0"
goto :eof

:start_mysql
if exist "%MYSQL_HOME%\bin\mysqld.exe" (
    echo      正在启动 %MYSQL_HOME%\bin\mysqld.exe （最小化窗口）
    start "MewChat MySQL" /min cmd /c ""%MYSQL_HOME%\bin\mysqld.exe" --console > "%TEMP%\mewchat-mysql.log" 2>&1"
    call :wait_port 3306 60
    goto :eof
)
rem 没有本地 mysqld，退回系统服务（需要管理员权限）
net start MySQL >nul 2>&1
if not errorlevel 1 call :wait_port 3306 30
goto :eof

:wait_port
rem %1=端口 %2=最多等待秒数（用 ping 做休眠：timeout 在输入被重定向时会失败）
set "PORT_UP="
set /a "WAITED=0"
:wait_loop
call :port_up %1
if defined PORT_UP goto :eof
if !WAITED! geq %2 goto :eof
ping -n 2 127.0.0.1 >nul 2>&1
set /a "WAITED+=1"
goto :wait_loop

:port_up
rem %1=端口；是否监听写入 PORT_UP（匹配 ":端口 " 以免误匹配 33060 这类）
set "PORT_UP="
for /f "tokens=*" %%l in ('netstat -an ^| findstr ":%~1 " ^| findstr "LISTENING" 2^>nul') do set "PORT_UP=1"
goto :eof

:wait_probe
rem %1=端口 %2=最多等待秒数；就绪（能应答）写入 PROBE_OK
set "PROBE_OK="
set /a "W2=0"
:probe_loop
call :probe_fake %1
if defined PROBE_OK goto :eof
if !W2! geq %2 goto :eof
ping -n 2 127.0.0.1 >nul 2>&1
set /a "W2+=1"
goto :probe_loop

:probe_fake
rem %1=端口：真发一次请求，拿到 HTTP 响应才算就绪。
rem 探测脚本写进临时 .ps1 再执行：PowerShell 命令里既有双引号又有花括号，
rem 塞进 for /f 的反引号里会被 cmd 的引号规则吃掉。
set "PROBE_OK="
set "PS1=%TEMP%\mewchat-probe.ps1"
> "%PS1%" echo try { $r = Invoke-WebRequest -Uri 'http://127.0.0.1:%~1/v1/chat/completions' -Method POST -Body '{"model":"probe","messages":[]}' -ContentType 'application/json' -TimeoutSec 5 -UseBasicParsing; exit 0 } catch { exit 1 }
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" >nul 2>&1
if not errorlevel 1 set "PROBE_OK=1"
goto :eof

:kill_port
rem %1=端口：清掉占用该端口的进程（很可能是上次残留的）
set "KILLED="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%~1 " ^| findstr "LISTENING" 2^>nul') do (
    if not "%%p"=="0" (
        taskkill /PID %%p /F >nul 2>&1
        if not errorlevel 1 (
            echo   [..] 已清掉占用 %~1 的残留进程（PID %%p）
            set "KILLED=1"
        )
    )
)
if defined KILLED ping -n 2 127.0.0.1 >nul 2>&1
goto :eof

:check_only
echo.
echo [OK] 预检通过，可以启动（去掉 check 参数即可）
goto :end_hold

:fail
echo.
echo 启动中止。按任意键关闭窗口。
pause >nul
exit /b 1

:end_hold
echo.
echo 按任意键关闭窗口。
pause >nul
endlocal
