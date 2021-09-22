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
