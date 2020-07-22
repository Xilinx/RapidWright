
BIN = bin
JARS = $(shell find `pwd`/jars -name '*.jar' | egrep -v 'javadoc|win64' | tr '\n' ':')

compile:
	rm -rf $(BIN)
	mkdir -p $(BIN)
	javac `find src -name '*.java'` -d $(BIN) -cp $(JARS)
