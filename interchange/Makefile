CAPNPC = capnpc
SRC = LogicalNetlist
DST = ../src/com/xilinx/rapidwright/interchange


all:
	mkdir -p $(DST)
	$(CAPNPC) -ojava:$(DST) $(SRC).capnp -I schema
