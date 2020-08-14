# RapidWright Interchange Format [Experimental]

Prototyping an interchange format to allow designs from other tools such as [VTR](https://github.com/verilog-to-routing/vtr-verilog-to-routing) and [nextpnr](https://github.com/YosysHQ/nextpnr) to read and write placed and routed designs with RapidWright.  This interface depends on [Cap'n Proto](https://capnproto.org/index.html) for serialization and you'll need to [install](https://capnproto.org/install.html) it with the [Java plugin](https://dwrensha.github.io/capnproto-java/index.html).

Current schema for logical netlist can be found here:
https://github.com/Xilinx/RapidWright/blob/interchange/interchange/LogicalNetlist.capnp


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
make
java com.xilinx.rapidwright.interchange.PhysicalNetlistExample
# download an example DCP file
wget http://www.rapidwright.io/docs/_downloads/picoblaze_best.zip
unzip -j picoblaze_best.zip pblock0.dcp pblock0.edf -d .
java com.xilinx.rapidwright.interchange.PhysicalNetlistExample pblock0.dcp pblock0_interchange.dcp
```

## How to Update RapidWright to the Most Recent Release (Assumes Linux)
```
cd $RAPIDWRIGHT_PATH
git pull
# resolve any issues
rm -rf data jars
curl -s https://api.github.com/repos/Xilinx/RapidWright/releases/latest | grep "browser_download_url.*_jars.zip" | cut -d : -f 2,3 | tr -d \" | wget -qi -
curl -s https://api.github.com/repos/Xilinx/RapidWright/releases/latest | grep "browser_download_url.*_data.zip" | cut -d : -f 2,3 | tr -d \" | wget -qi -
unzip rapidwright_jars.zip
unzip rapidwright_data.zip
rm jars/qtjambi-win64-msvc2005x64-4.5.2_01.jar
make
export CLASSPATH=$RAPIDWRIGHT_PATH/bin:$(echo $RAPIDWRIGHT_PATH/jars/*.jar | tr ' ' ':')
```

## How to Re-generate Cap'n Proto Java Code from Schema
```
cd interchange && make && cd ..
make
```
