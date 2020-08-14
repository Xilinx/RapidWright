
BIN = bin
JARS = $(shell find `pwd`/jars -name '*.jar' | egrep -v 'javadoc|win64' | tr '\n' ':')

compile:
	rm -rf $(BIN)
	mkdir -p $(BIN)
	javac `find src -name '*.java'` -d $(BIN) -cp $(JARS)
	echo "export CLASSPATH=`pwd`/bin:$(shell echo `pwd`/jars/*.jar | tr ' ' ':')" > $(BIN)/rapidwright_classpath.sh

update_jars:
	rm -rf data jars
	curl -s https://api.github.com/repos/Xilinx/RapidWright/releases/latest | grep "browser_download_url.*_jars.zip" | cut -d : -f 2,3 | tr -d \" | wget -qi -
	curl -s https://api.github.com/repos/Xilinx/RapidWright/releases/latest | grep "browser_download_url.*_data.zip" | cut -d : -f 2,3 | tr -d \" | wget -qi -
	unzip rapidwright_jars.zip
	unzip rapidwright_data.zip
	rm jars/qtjambi-win64-msvc2005x64-4.5.2_01.jar rapidwright_jars.zip rapidwright_data.zip
	make -C . compile
