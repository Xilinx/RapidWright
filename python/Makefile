
LIC_FILE=LICENSE.TXT


.PHONY: test_upload package 

package:
	cp ../$(LIC_FILE) .
	python3 -m build

test_upload:
	twine upload --repository testpypi dist/*

clean:
	-rm -rf dist
	-rm -rf src/rapidwright.egg-info
	-rm -rf src/rapidwright/__pycache__
	-rm -rf build
	-rm -rf $(LIC_FILE)
