
BIN = bin
CLASSPATH = $(shell find `pwd`/jars -name '*.jar' | egrep -v 'javadoc|win64' | tr '\n' ':')
JARFILES = $(shell find `pwd`/jars -name '*.jar' | egrep -v 'javadoc|win64' | tr '\n' ' ')


SOURCES := $(shell find ./src -name '*.java'  )
CLASSES := $(shell find ./src -name '*.java' | grep -v package-info.java | sed 's=^./src=./bin=' | sed 's/.java$$/.class/')


.PHONY: compile update_jars
compile: $(CLASSES)
$(CLASSES): $(SOURCES) $(JARFILES)
	rm -rf $(BIN)
	mkdir -p $(BIN)
	javac -source 8 -target 8 $(SOURCES) -d $(BIN) -cp $(CLASSPATH)
	echo "export CLASSPATH=`pwd`/bin:$(shell echo `pwd`/jars/*.jar | tr ' ' ':')" > $(BIN)/rapidwright_classpath.sh

update_jars:
	./gradlew update_jars
