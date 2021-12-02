# RapidWright  

Try RapidWright in your browser: [![Binder](https://mybinder.org/badge_logo.svg)](https://mybinder.org/v2/gh/clavin-xlnx/RapidWright-binder/master?urlpath=%2Fnotebooks%2FHelloWorld.ipynb)

![Build](https://github.com/Xilinx/RapidWright/workflows/Build/badge.svg)

RapidWright is an open source project from Xilinx Research Labs that
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

### Easiest way to Setup a RapidWright Repo Locally:

```
wget http://www.rapidwright.io/docs/_downloads/rapidwright-installer.jar
java -jar rapidwright-installer.jar -t
source rapidwright.sh
cd RapidWright
```

More details here:
http://www.rapidwright.io/docs/Automatic_Install.html#automatic-install


### How to Update RapidWright to the Most Recent Release

```
cd $RAPIDWRIGHT_PATH
git pull
# resolve any issues
gradlew update_jars
```
