#!/bin/bash -e
# This script is a simple wrapper for invoking RapidWright entry points.
#
# Usage:
#
#    invoke_rapidwright.sh <class entry point> <arguments to entry point ...>
#
#  This script requires that RAPIDWRIGHT_PATH environment variable is defined.
#
#  RAPIDWRIGHT_PATH should point to the installation folder for RapidWright.
#
#  A Java runtime environment is required.  By default script will invoke
#  the executable "java" if it is on the path.  If "java" is not on the path,
#  the environment variable JAVA is used.  If "java" is not on the path and
#  the environment variable JAVA is not defined or does not point to a valid
#  executable, an error is generated.

if [ "$#" -eq 0 ]; then
    echo "Usage: invoke_rapidwright.sh <class entry point> <arguments to entry point ...>"
    exit 1
fi

# Check if RAPIDWRIGHT_PATH exists and points to a valid directory.
if [ -z ${RAPIDWRIGHT_PATH+x} ]; then
    echo "RAPIDWRIGHT_PATH environment variable is not set."
    exit 1
fi

if [ ! -d "${RAPIDWRIGHT_PATH}" ]; then
    echo "RAPIDWRIGHT_PATH ('${RAPIDWRIGHT_PATH}') is not a valid directory?"
    exit 1
fi

# Make sure rapidwright_path.sh is found at expected location.
if [ ! -f "${RAPIDWRIGHT_PATH}/bin/rapidwright_classpath.sh" ]; then
    echo "${RAPIDWRIGHT_PATH}/bin/rapidwright_classpath.sh was not found, check if RapidWright has been built."
    exit 1
fi

# Find JRE executable, defaulting to executable java if JAVA environment
# variable is not set.
JAVA="${JAVA:-$(which java)}"

if [ "${JAVA}" == "" ]; then
    echo "Environment variable JAVA not defined, and java is not on path?"
    exit 1
fi

if [[ ! -x "${JAVA}" ]]; then
    echo "JAVA ('${JAVA}') is not executable?"
    exit 1
fi

# Set the CLASSPATH to include RapidWright jars.
source "${RAPIDWRIGHT_PATH}/bin/rapidwright_classpath.sh"

# If we have a Java version that supports --add-exports, then set it in
# order to avoid issues with Kryo accessing sun.nio.ch
EXTRA_JAVA_OPTS=
if ("${JAVA}" --add-exports 2>&1 | grep 'requires modules to be specified' > /dev/null); then
    # This text inside the error means it is supported as an option
    EXTRA_JAVA_OPTS="--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
fi

# Invoke JRE with specified entry point and arguments.  Return the exit code
# from java to caller of script.
set +e
"${JAVA}" ${EXTRA_JAVA_OPTS} "$@"
exit $?
