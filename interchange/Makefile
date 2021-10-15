CAPNPC = capnpc
INTERCHANGE_NAME = interchange
INTERCHANGE_REPO_NAME = fpga-$(INTERCHANGE_NAME)-schema
INTERCHANGE_REPO_PATH = $(realpath .)/$(INTERCHANGE_REPO_NAME)
SRCS = LogicalNetlist.capnp DeviceResources.capnp PhysicalNetlist.capnp References.capnp
RAPIDWRIGHT_DIR = $(dir $(realpath .))
DST = $(RAPIDWRIGHT_DIR)/src/com/xilinx/rapidwright/$(INTERCHANGE_NAME)
JARS_DIR = $(RAPIDWRIGHT_DIR)/jars
JAVA_CP_RUNTIME = $(JARS_DIR)/runtime-0.1.4.jar
JAVA_SCHEMA_DIR = $(realpath .)/schema
JAVA_SCHEMA = $(JAVA_SCHEMA_DIR)/capnp/java.capnp

all: $(JAVA_CP_RUNTIME) $(JAVA_SCHEMA) | $(INTERCHANGE_REPO_PATH)
	mkdir -p $(DST)
	cd $(INTERCHANGE_REPO_PATH)/$(INTERCHANGE_NAME) && $(CAPNPC) -ojava:$(DST) $(SRCS) -I $(JAVA_SCHEMA_DIR)

$(JAVA_CP_RUNTIME):
	mkdir -p $(JARS_DIR)
	wget https://search.maven.org/remotecontent?filepath=org/capnproto/runtime/0.1.4/runtime-0.1.4.jar -O $(JAVA_CP_RUNTIME)

$(JAVA_SCHEMA):
	mkdir -p $(JAVA_SCHEMA_DIR)/capnp
	wget https://raw.githubusercontent.com/capnproto/capnproto-java/master/compiler/src/main/schema/capnp/java.capnp -O $(JAVA_SCHEMA)

$(INTERCHANGE_REPO_PATH):
	cd $(dir $(INTERCHANGE_REPO_PATH)) && git clone https://github.com/chipsalliance/$(INTERCHANGE_REPO_NAME).git

update_interchange: | $(INTERCHANGE_REPO_PATH)
	cd $(INTERCHANGE_REPO_PATH) && git pull

