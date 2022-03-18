###############################################################################
# Notes on Release:
# Test PyPi might already have some existing versions that conflict with the 
# version to be released as past fix/update cycles consume future version
# numbers.  Thus, to test a new release, consult:
# https://test.pypi.org/manage/project/rapidwright/releases/
# to find the next available release version.
#  1. Run 'make update_version' to update the files with the version to release
#  2. Modify setup.py to the Test PyPi available version number (if needed)
#  3. Run 'make package'
#  4. Run 'make test_upload'
#  5. Test the package download and install in a venv from Test PyPi to ensure
#     The package and release is good. For example:
#     $ unset RAPIDWRIGHT_PATH
#     $ unset CLASSPATH
#     $ mkdir test_rapidwright_venv; cd test_rapidwright_venv
#     $ python3 -mvenv env
#     $ source env/bin/activate
#     $ pip install jpype1
#     $ python3 -m pip install --index-url https://test.pypi.org/simple/ rapidwright
#     $ python3 <run test script>
#  6. If step 2 was run, run 'make clean; make update_version; make package'
#  7. Upload to PyPi with 'make upload_to_pypi'
###############################################################################
LIC_FILE=LICENSE.TXT
RWVER=


.PHONY: test_upload package 

package:
	cp ../$(LIC_FILE) .
	python3 -m build

update_version:
	$(eval RWVER=$(shell java com.xilinx.rapidwright.util.RapidWright -c "from com.xilinx.rapidwright.device import Device; print(Device.RAPIDWRIGHT_VERSION)"))
	@echo $(RWVER)
	sed -i s/version=.*$$/version=\'$(RWVER)\',/ setup.py
	sed -i s/version=.*$$/version=\'$(RWVER)\'/ src/rapidwright/rapidwright.py

test_upload:
	twine upload --repository testpypi dist/*

upload_to_pypi:
	twine upload dist/*


clean:
	-rm -rf dist
	-rm -rf src/rapidwright.egg-info
	-rm -rf src/rapidwright/__pycache__
	-rm -rf build
	-rm -rf $(LIC_FILE)
