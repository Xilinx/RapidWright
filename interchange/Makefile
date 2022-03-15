CAPNPC = capnpc
INTERCHANGE_NAME = interchange
INTERCHANGE_REPO_NAME = fpga-$(INTERCHANGE_NAME)-schema
INTERCHANGE_REPO_PATH = $(realpath .)/$(INTERCHANGE_REPO_NAME)
SRCS = LogicalNetlist.capnp DeviceResources.capnp PhysicalNetlist.capnp References.capnp
RAPIDWRIGHT_DIR = $(dir $(realpath .))
DST = $(RAPIDWRIGHT_DIR)/src/com/xilinx/rapidwright/$(INTERCHANGE_NAME)
JARS_DIR = $(RAPIDWRIGHT_DIR)/jars
RUNTIME_VER = $(shell grep org.capnproto:runtime: ../common.gradle | awk -F: '{print $$3}' | sed "s/'//" )
JAVA_CP_RUNTIME = $(JARS_DIR)/runtime-$(RUNTIME_VER).jar
JAVA_SCHEMA_DIR = $(realpath .)/schema
JAVA_SCHEMA = $(JAVA_SCHEMA_DIR)/capnp/java.capnp
USER_FULL_NAME = $(shell getent passwd $(USER) | awk -F: '{print $$5}' | awk -F, '{print $$1}')
CURR_YEAR = $(shell date +%Y)

all: $(JAVA_CP_RUNTIME) $(JAVA_SCHEMA) | $(INTERCHANGE_REPO_PATH)
	mkdir -p $(DST)
	echo "Cap'n Proto Java Runtime Version: $(RUNTIME_VER), USER FULL NAME=$(USER_FULL_NAME)"
	cd $(INTERCHANGE_REPO_PATH)/$(INTERCHANGE_NAME) && $(CAPNPC) -ojava:$(DST) $(SRCS) -I $(JAVA_SCHEMA_DIR)

$(JAVA_CP_RUNTIME):
	mkdir -p $(JARS_DIR)
	wget https://search.maven.org/remotecontent?filepath=org/capnproto/runtime/$(RUNTIME_VER)/runtime-$(RUNTIME_VER).jar -O $(JAVA_CP_RUNTIME)

$(JAVA_SCHEMA):
	mkdir -p $(JAVA_SCHEMA_DIR)/capnp
	wget https://raw.githubusercontent.com/capnproto/capnproto-java/master/compiler/src/main/schema/capnp/java.capnp -O $(JAVA_SCHEMA)

$(INTERCHANGE_REPO_PATH):
	cd $(dir $(INTERCHANGE_REPO_PATH)) && git clone https://github.com/chipsalliance/$(INTERCHANGE_REPO_NAME).git

update_interchange: | $(INTERCHANGE_REPO_PATH)
	cd $(INTERCHANGE_REPO_PATH) && git pull

