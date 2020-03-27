# RapidWright Interchange Format 

Prototyping an interchange format to allow designs from other tools such as [VTR](https://github.com/verilog-to-routing/vtr-verilog-to-routing) and [nextpnr](https://github.com/YosysHQ/nextpnr) to read and write placed and routed designs with RapidWright.  

## Easiest way to Setup a RapidWright Repo Locally:
```
wget http://www.rapidwright.io/docs/_downloads/rapidwright-installer.jar
java -jar rapidwright-installer.jar
```
More details here: 
http://www.rapidwright.io/docs/Automatic_Install.html#automatic-install

## How to Run Example
```
git checkout interchange
gradle build
java com.xilinx.rapidwright.interchange.LogicalNetlistExample
```

## How to Re-generate Cap'n Proto Java Code from Schema
```
cd interchange
make
```
