import unittest
import rapidwright
from com.xilinx.rapidwright.design import Design
import java

class TestDesign(unittest.TestCase):

	# Check that this does not terminate the JVM (Issue #259)
	def testReadCheckpointNotFound(self):
		with self.assertRaises(java.io.UncheckedIOException):
			Design.readCheckpoint('does_not_exist.dcp')
