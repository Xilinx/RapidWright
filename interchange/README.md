# FPGA Interchange Format for RapidWright

The [FPGA Interchange Format](http://www.rapidwright.io/docs/FPGA_Interchange_Format.html) provides an interface for other tools such as [DREAMPlaceFPGA](https://github.com/rachelselinar/DREAMPlaceFPGA) to import and export placed and routed designs through RapidWright and into Vivado.  This interface depends on [Cap'n Proto](https://capnproto.org/index.html) for serialization and you'll need to [install](https://capnproto.org/install.html) it with the [Java plugin](https://dwrensha.github.io/capnproto-java/index.html).

Current schema for the interchange format can be found here:
https://github.com/chipsalliance/fpga-interchange-schema

## Easiest way to Setup a RapidWright Repo Locally:
```
git clone https://github.com/Xilinx/RapidWright.git
cd RapidWright
./gradlew compileJava
```

More details here:
http://www.rapidwright.io/docs/Install.html

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
