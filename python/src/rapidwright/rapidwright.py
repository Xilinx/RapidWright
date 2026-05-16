################################################################################ 
# Copyright (c) 2021 Xilinx, Inc. 
# All rights reserved.
#
# Author: Chris Lavin, Xilinx Research Labs.
#
# This file is part of RapidWright. 
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#     http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
################################################################################
import jpype
import jpype.imports
from jpype.types import *
from typing import List, Optional
import os, urllib.request, platform, shutil

version='2025.2.1'
java_home_var='JAVA_HOME'

def _find_java_home():
    # Prioritize 'java' on PATH if JAVA_HOME not set
    java_exec = shutil.which('java')
    if java_exec:
        java_real_path = os.path.realpath(java_exec)
        java_home = os.path.dirname(os.path.dirname(java_real_path))
        if os.path.isdir(java_home):
            return java_home        
    return None
    
def start_jvm():
    os_str = 'lin64'
    if platform.system() == 'Windows':
        os_str = 'win64'
    kwargs = {}

    # JPype prioritizes JAVA_HOME over java on PATH, let's set JAVA_HOME if it is not set
    if not os.environ.get(java_home_var):
        java_home = _find_java_home()
        if java_home:
            os.environ[java_home_var] = java_home
    
    if not os.environ.get('RAPIDWRIGHT_PATH'):
        dir_path = os.path.dirname(os.path.realpath(__file__))
        file_name = "rapidwright-"+version+"-standalone-"+os_str+".jar"
        classpath = os.path.join(dir_path,file_name)
        if not os.path.isfile(classpath):
            url = "http://github.com/Xilinx/RapidWright/releases/download/v"+version+"-beta/" + file_name
            urllib.request.urlretrieve(url,classpath)
        kwargs['classpath'] = classpath
    if not os.environ.get('CLASSPATH') and os.environ.get('RAPIDWRIGHT_PATH'):
        rwPath = os.environ.get('RAPIDWRIGHT_PATH')
        classpath = rwPath + "/bin:" + rwPath + "/jars/*"
        print("ERROR: RAPIDWRIGHT_PATH is set but CLASSPATH is not set.  Please set CLASSPATH=" + classpath)
        exit(1)
    if not jpype.isJVMStarted():
        jpype.startJVM(**kwargs)

def block_system_exit_calls():
    from com.xilinx.rapidwright.util import FileTools
    FileTools.blockSystemExitCalls()

start_jvm()
block_system_exit_calls()
