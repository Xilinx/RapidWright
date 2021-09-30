
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
