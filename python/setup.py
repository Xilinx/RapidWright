#!/usr/bin/env python
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
from __future__ import absolute_import
from __future__ import print_function

import io
import re
from glob import glob
from os.path import basename
from os.path import dirname

from setuptools import find_packages
from setuptools import setup

setup(
    name='rapidwright',
    version='2021.1.0',
    license='Apache 2.0 and Others',
    description='Xilinx RapidWright Framework Wrapped for Python.',
    long_description='',
    author='Chris Lavin',
    author_email='chris.lavin@xilinx.com',
    url='https://github.com/Xilinx/RapidWright',
    packages=find_packages(where='src'),
    package_dir={'': 'src'},
#    package_data={'' : ['*.jar']},
#    include_package_data=True,
    zip_safe=False,
    classifiers=[
        # complete classifier list: http://pypi.python.org/pypi?%3Aaction=list_classifiers
        'Operating System :: Unix',
        'Operating System :: POSIX',
        'Operating System :: Microsoft :: Windows',
        'Programming Language :: Python',
        'Programming Language :: Python :: 3',
        'Programming Language :: Python :: 3.5',
        'Programming Language :: Python :: 3.6',
        'Programming Language :: Python :: 3.7',
        'Programming Language :: Python :: 3.8',
        'Programming Language :: Python :: 3.9',
        'Topic :: Utilities',
    ],
    project_urls={
        'Changelog': 'https://github.com/Xilinx/RapidWright/blob/master/RELEASE_NOTES.TXT',
        'Issue Tracker': 'https://github.com/Xilinx/RapidWright/issues',
    },
    keywords=[
        'rapidwright', 'xilinx', 'fpga', 'design checkpoint', 'DCP', 'placement', 'routing',
    ],
    python_requires='!=3.0.*, !=3.1.*, !=3.2.*, !=3.3.*, !=3.4.*',
    install_requires=[
        ["jpype1"]
    ],
    setup_requires=[
        '',
    ],
    entry_points={
        'console_scripts': [
            'rapidwright = rapidwright.cli:main',
        ]
    },
)
