CAPNPC = capnpc
INTERCHANGE_NAME = interchange
INTERCHANGE_REPO_NAME = fpga-$(INTERCHANGE_NAME)-schema
INTERCHANGE_REPO_PATH = $(shell pwd)/$(INTERCHANGE_REPO_NAME)
INTERCHANGE_CLONE_PATH = https://github.com/SymbiFlow/$(INTERCHANGE_REPO_NAME).git
SRCS = LogicalNetlist.capnp DeviceResources.capnp PhysicalNetlist.capnp References.capnp
SRCS_WITH_PATH = $(addprefix $(INTERCHANGE_REPO_PATH)/$(INTERCHANGE_NAME)/, $(SRCS))
DST = ../src/com/xilinx/rapidwright/$(INTERCHANGE_NAME)
JAVA_CP_RUNTIME = ../jars/runtime-0.1.4.jar
JAVA_SCHEMA_DIR = schema/capnp
JAVA_SCHEMA = $(JAVA_SCHEMA_DIR)/java.capnp

all: $(JAVA_CP_RUNTIME) $(JAVA_SCHEMA) $(INTERCHANGE_REPO_PATH)
	mkdir -p $(DST)
	$(CAPNPC) -ojava:$(DST) $(SRCS_WITH_PATH) -I schema

$(JAVA_CP_RUNTIME):
	wget https://search.maven.org/remotecontent?filepath=org/capnproto/runtime/0.1.4/runtime-0.1.4.jar -O $(JAVA_CP_RUNTIME)

$(JAVA_SCHEMA):
	mkdir -p $(JAVA_SCHEMA_DIR)
	wget https://raw.githubusercontent.com/capnproto/capnproto-java/master/compiler/src/main/schema/capnp/java.capnp -O $(JAVA_SCHEMA)

$(INTERCHANGE_REPO_PATH):
	cd $(INTERCHANGE_REPO_PATH)/.. && git clone $(INTERCHANGE_CLONE_PATH)

update_interchange: $(INTERCHANGE_REPO_PATH)
	cd $(INTERCHANGE_REPO_PATH) && git pull

