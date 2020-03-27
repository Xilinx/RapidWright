# RapidWright Interchange Format [Experimental]

Prototyping an interchange format to allow designs from other tools such as [VTR](https://github.com/verilog-to-routing/vtr-verilog-to-routing) and [nextpnr](https://github.com/YosysHQ/nextpnr) to read and write placed and routed designs with RapidWright.  This interface depends on [Cap'n Proto](https://capnproto.org/index.html) for serialization and you'll need to [install](https://capnproto.org/install.html) it with the [Java plugin](https://dwrensha.github.io/capnproto-java/index.html).

## Easiest way to Setup a RapidWright Repo Locally:
```
wget http://www.rapidwright.io/docs/_downloads/rapidwright-installer.jar
java -jar rapidwright-installer.jar -t
source rapidwright.sh 
cd RapidWright
```
More details here: 
http://www.rapidwright.io/docs/Automatic_Install.html#automatic-install

## How to Run Example
```
git checkout interchange
cd interchange && make && cd ..
gradle build
export CLASSPATH=$CLASSPATH:`pwd`/jars/runtime-0.1.4.jar
java com.xilinx.rapidwright.interchange.LogicalNetlistExample
```

## How to Re-generate Cap'n Proto Java Code from Schema
```
cd interchange && make && cd ..
gradle build
```
