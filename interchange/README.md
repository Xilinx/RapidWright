# FPGA Interchange Format for RapidWright

The FPGA interchange format allows designs from other tools such as [VTR](https://github.com/verilog-to-routing/vtr-verilog-to-routing) and [nextpnr](https://github.com/YosysHQ/nextpnr) to read and write placed and routed designs with RapidWright.  This interface depends on [Cap'n Proto](https://capnproto.org/index.html) for serialization and you'll need to [install](https://capnproto.org/install.html) it with the [Java plugin](https://dwrensha.github.io/capnproto-java/index.html).

Current schema for the interchange format can be found here:
https://github.com/SymbiFlow/fpga-interchange-schema

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
make -C interchange
make
java com.xilinx.rapidwright.interchange.PhysicalNetlistExample
# download an example DCP file
wget http://www.rapidwright.io/docs/_downloads/picoblaze_best.zip
unzip -j picoblaze_best.zip pblock0.dcp pblock0.edf -d .
java com.xilinx.rapidwright.interchange.PhysicalNetlistExample pblock0.dcp pblock0_interchange.dcp
```

## How to Re-generate Cap'n Proto Java Code from Schema
```
cd interchange && make && cd ..
make
```
