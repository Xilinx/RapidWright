# RapidWright  

Try RapidWright in your browser: [![Binder](https://mybinder.org/badge_logo.svg)](https://mybinder.org/v2/gh/clavin-xlnx/RapidWright-binder/master?urlpath=%2Fnotebooks%2FHelloWorld.ipynb)

![Build](https://github.com/Xilinx/RapidWright/workflows/Build/badge.svg)

RapidWright is an open source project from AMD Research and
Advanced Development (formerly Xilinx Research Labs) that
provides a new bridge to Vivado through reading and writing design
checkpoint (DCP) files.  Its mission is to enable power users greater
flexibility in customizing solutions to their unique implementation
challenges.

RapidWright also provides a new design methodology leveraging
pre-implemented modules (modules that have been synthesized, placed
and routed out-of-context).  These pre-implemented modules can be
cached, replicated and relocated using the RapidWright framework. We
see pre-implemented modules as a way to build systematic shells and
overlays and a core piece of strategy in achieving near-spec
performance.

If you run into issues, feel free to file an issue on the [Github
issue tracker](https://github.com/Xilinx/RapidWright/issues/new), or,
for more broad questions/requests, post on our [discussion
forum](https://github.com/Xilinx/RapidWright/discussions). [Documentation](http://www.rapidwright.io/docs/index.html)
and [Javadoc](http://www.rapidwright.io/javadoc/index.html) reference is also available.

For more information, please see http://www.rapidwright.io.

NOTE: RapidWright is not an official product from Xilinx and designs
created or derived from it are not warranted. Please see
LICENSE.TXT for full details.

## Installation

### Easiest way to Checkout and Compile a RapidWright Repo Locally:

```
git clone https://github.com/Xilinx/RapidWright.git
cd RapidWright
./gradlew compileJava
```

More details here:
http://www.rapidwright.io/docs/Install.html


### How to Update RapidWright to the Latest Revision

```
cd $RAPIDWRIGHT_PATH
git pull
# resolve any issues
./gradlew updateJars
```

### Python Setup

```
pip install rapidwright
```

More details here:
http://www.rapidwright.io/docs/Install_RapidWright_as_a_Python_PIP_Package.html

### Development setup

RapidWright includes a git pre-commit hook that runs some quick checks before commits. After cloning the repository, the hook is not enabled by default. To enable it, run this command:

```
make enable_pre_commit_hook
```

You will only have to do this once.
