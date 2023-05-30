@echo off
REM  Copyright (c) 2023, Advanced Micro Devices, Inc.
REM  All rights reserved.
REM 
REM  Author: Chris Lavin, AMD Research and Advanced Development.
REM 
REM  This file is part of RapidWright.
REM 
REM  Licensed under the Apache License, Version 2.0 (the "License");
REM  you may not use this file except in compliance with the License.
REM  You may obtain a copy of the License at
REM 
REM      http://www.apache.org/licenses/LICENSE-2.0
REM 
REM  Unless required by applicable law or agreed to in writing, software
REM  distributed under the License is distributed on an "AS IS" BASIS,
REM  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
REM  See the License for the specific language governing permissions and
REM  limitations under the License.

REM Check that the main jar has been built
REM NOTE: Does not check that it is up-to-date
SET "BAT_SOURCE=%~dp0"
SET "MAIN_JAR=%BAT_SOURCE%build\libs\main.jar"
IF EXIST %MAIN_JAR% GOTO JAR_EXISTS
echo "RapidWright not yet compiled. Please run './gradlew compileJava' from '%BAT_SOURCE%'"
EXIT /B 1
  
:JAR_EXISTS
SET PRINT_HELP=FALSE
IF "%~1"=="" SET PRINT_HELP=TRUE
IF "%~1" == "--help" SET PRINT_HELP=TRUE
IF "%~1" == "-h" SET PRINT_HELP=TRUE
IF "%~1" == "/?" SET PRINT_HELP=TRUE
IF "%PRINT_HELP%"=="FALSE" GOTO RUN_STUFF
echo.  rapidwright com.xilinx.rapidwright.^<ClassName^> -- to execute main() method of Java class
echo.  rapidwright ^<application^>                      -- to execute a specific application
echo.  rapidwright --list-apps                        -- to list all available applications
echo.  rapidwright Jython                             -- to enter interactive Jython shell
echo.  rapidwright Jython -c "..."                    -- to execute specific Jython command
EXIT /B 0

:RUN_STUFF
SET ARG1=%~1
IF "%ARG1:~0,23%" == "com.xilinx.rapidwright." GOTO RUN_APPS

java -jar %MAIN_JAR%
EXIT /B 0

:RUN_APPS
java -cp %MAIN_JAR% %*
EXIT /B 0
