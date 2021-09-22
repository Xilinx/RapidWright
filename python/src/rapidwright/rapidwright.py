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
import os, urllib.request, platform
import os.path

def start_jvm(add_classpath=None, jvm_flags=[]):
    os_str = 'lin64'
    if platform.system() == 'Windows':
        os_str = 'win64'
    dir_path = os.path.dirname(os.path.realpath(__file__))
    file_name = "rapidwright-2021.1.0-standalone-"+os_str+".jar"
    classpath = os.path.join(dir_path,file_name)
    print("file=" + classpath)
    if not os.path.isfile(classpath):
        url = "http://github.com/Xilinx/RapidWright/releases/download/v2021.1.0-beta/" + file_name
        urllib.request.urlretrieve(url,classpath)

    if add_classpath != None:
        classpath = classpath.insert(0, [add_classpath])
        
    if not jpype.isJVMStarted():
        jpype.startJVM(classpath=classpath, *jvm_flags)

start_jvm()
