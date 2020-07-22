CAPNPC = capnpc
SRCS = LogicalNetlist.capnp DeviceResources.capnp PhysicalNetlist.capnp
DST = ../src/com/xilinx/rapidwright/interchange
JAVA_CP_RUNTIME=../jars/runtime-0.1.4.jar
JAVA_SCHEMA_DIR=schema/capnp
JAVA_SCHEMA=$(JAVA_SCHEMA_DIR)/java.capnp

all: $(JAVA_CP_RUNTIME) $(JAVA_SCHEMA)
	mkdir -p $(DST)
	$(CAPNPC) -ojava:$(DST) $(SRCS) -I schema

$(JAVA_CP_RUNTIME):
	wget https://search.maven.org/remotecontent?filepath=org/capnproto/runtime/0.1.4/runtime-0.1.4.jar -O $(JAVA_CP_RUNTIME)

$(JAVA_SCHEMA):
	mkdir -p $(JAVA_SCHEMA_DIR)
	wget https://raw.githubusercontent.com/capnproto/capnproto-java/master/compiler/src/main/schema/capnp/java.capnp -O $(JAVA_SCHEMA)
