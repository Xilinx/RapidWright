

test_upload:
	twine upload --repository testpypi dist/*

package:
	python3 -m build

clean:
	-rm -rf dist
	-rm -rf src/rapidwright.egg-info
	-rm -rf src/rapidwright/__pycache__
	-rm -rf build
