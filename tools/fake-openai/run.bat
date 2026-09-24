@echo off
rem ============================================================================
rem  本地假 OpenAI 端点启动脚本（Windows）
rem
rem  用法：双击本文件，或由 start.bat 调起。参数：端口号，默认 18124。
rem
rem  [!] 为什么不用 `java FakeOpenAi.java 18124` 这种单文件源码启动方式：
rem      它会先起一个 JVM 编译、再 fork 出第二个 JVM 运行程序；这套父子结构在
rem      cmd 环境下实测会起出"端口在监听、请求无响应"的进程（curl 直接 EOF）。
rem      改成"先 javac 到 build 目录、再 java -cp 运行"，只有一个 JVM，行为正常。
rem
rem  [!] 本文件是 GBK 编码（与项目其它 Windows 脚本一致）。
rem ============================================================================

setlocal
cd /d "%~dp0"
set "PORT=%~1"
if "%PORT%"=="" set "PORT=18124"

echo 编译本地假 OpenAI 端点...
if not exist "build" mkdir build
javac -encoding UTF-8 -d build FakeOpenAi.java
if errorlevel 1 (
    echo.
    echo [X] 编译失败：请确认 javac 在 PATH 中（需要 JDK，而不只是 JRE）。
    pause
    exit /b 1
)

echo 启动中（端口 %PORT%）...
echo 请求日志 fake-openai.log / 控制台输出 fake-openai-console.log
java -cp build FakeOpenAi %PORT% > fake-openai-console.log 2>&1
echo.
echo 假端点已退出。日志见同目录 fake-openai.log
pause
endlocal
