
BIN = bin
CLASSPATH = $(shell find `pwd`/jars -name '*.jar' 2>/dev/null | egrep -v 'javadoc|win64' | tr '\n' ':')
JARFILES = $(shell find `pwd`/jars -name '*.jar' 2>/dev/null | egrep -v 'javadoc|win64' | tr '\n' ' ')


SOURCES := $(shell find ./src -name '*.java'  )
CLASSES := $(shell find ./src -name '*.java' | grep -v package-info.java | sed 's=^./src=./bin=' | sed 's/.java$$/.class/')

USER_FULL_NAME = $(shell getent passwd $(USER) | awk -F: '{print $$5}' | awk -F, '{print $$1}')
CURR_YEAR = $(shell date +%Y)
TMP_HEADER = TMP_HEADER_TXT


.PHONY: compile update_jars ensure_headers check_headers pre_commit enable_pre_commit_hook
compile: $(CLASSES)
$(CLASSES): $(SOURCES) $(JARFILES)
	rm -rf $(BIN)/com
	javac -source 8 -target 8 $(SOURCES) -d $(BIN) -cp $(CLASSPATH)
	echo "export CLASSPATH=`pwd`/bin:$(shell echo `pwd`/jars/*.jar | tr ' ' ':')" > $(BIN)/rapidwright_classpath.sh

update_jars:
	./gradlew update_jars

ensure_headers:
	cat doc/SOURCE_HEADER.TXT | sed 's/$${user}/$(USER_FULL_NAME)/' | sed 's/$${year}/$(CURR_YEAR)/' > $(TMP_HEADER)
	for f in `find {test,src} -name '*.java'`; do if ! grep -q 'Apache' $$f; then cat $(TMP_HEADER) $$f > $$f.new && mv $$f.new $$f; fi done
	@rm $(TMP_HEADER)

check_headers:
	@ NO_LICENSE_FILES=$$(git grep --files-without-match 'Apache' -- '*.java'); \
	if [ ! -z "$$NO_LICENSE_FILES" ] ;\
	then \
		echo "These files are missing a license header:" ;\
		echo ;\
		echo "$$NO_LICENSE_FILES" | sed 's/^/    /' ;\
		echo ;\
		echo "Use make ensure_headers to automatically add the header." ;\
		exit 1;\
	fi

check_tabs:
# Yes, that is an actual tab character in quotes, otherwise git grep doesn't work
	@ FILES_CONTAINING_TABS=$$(git grep "	" -- '*.java'); \
	if [ ! -z "$$FILES_CONTAINING_TABS" ] ;\
	then \
		echo "These files contain tab characters, please replace tabs with 4 spaces:" ;\
		echo ;\
		echo "$$FILES_CONTAINING_TABS" | sed 's/^/    /' ;\
		echo ;\
		echo "Use make check_tabs to automatically detect tab characters." ;\
		exit 1;\
	fi


pre_commit: check_headers check_tabs

enable_pre_commit_hook:
	@ hook_file=$$(git rev-parse --git-path hooks/pre-commit) && \
	echo "#!/bin/bash" > $$hook_file && \
	echo "make pre_commit" >> $$hook_file && \
	chmod +x $$hook_file && \
	echo "Enabled pre-commit hook at $$hook_file"
