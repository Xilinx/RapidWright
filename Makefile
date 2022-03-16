
BIN = bin
CLASSPATH = $(shell find `pwd`/jars -name '*.jar' | egrep -v 'javadoc|win64' | tr '\n' ':')
JARFILES = $(shell find `pwd`/jars -name '*.jar' | egrep -v 'javadoc|win64' | tr '\n' ' ')


SOURCES := $(shell find ./src -name '*.java'  )
CLASSES := $(shell find ./src -name '*.java' | grep -v package-info.java | sed 's=^./src=./bin=' | sed 's/.java$$/.class/')

USER_FULL_NAME = $(shell getent passwd $(USER) | awk -F: '{print $$5}' | awk -F, '{print $$1}')
CURR_YEAR = $(shell date +%Y)
TMP_HEADER = TMP_HEADER_TXT


.PHONY: compile update_jars
compile: $(CLASSES)
$(CLASSES): $(SOURCES) $(JARFILES)
	rm -rf $(BIN)
	mkdir -p $(BIN)
	javac -source 8 -target 8 $(SOURCES) -d $(BIN) -cp $(CLASSPATH)
	echo "export CLASSPATH=`pwd`/bin:$(shell echo `pwd`/jars/*.jar | tr ' ' ':')" > $(BIN)/rapidwright_classpath.sh

update_jars:
	./gradlew update_jars

ensure_headers:
	cat doc/SOURCE_HEADER.TXT | sed 's/$${user}/$(USER_FULL_NAME)/' | sed 's/$${year}/$(CURR_YEAR)/' > $(TMP_HEADER)
	for f in `find {test,src} -name *.java`; do if ! grep -q 'Apache' $$f; then cat $(TMP_HEADER) $$f > $$f.new && mv $$f.new $$f; fi done
	@rm $(TMP_HEADER)
