import unittest
import rapidwright
from java.lang import System
from java.lang import SecurityException
from com.xilinx.rapidwright.util import FileTools
 
class TestFileTools(unittest.TestCase):

    # Test that JVM does not exit when System.exit() is called
    @unittest.skipIf(FileTools.getJavaVersion() > 17, "JVM args require explicit permission for a \
    custom SecurityManager") 
    def testSystemExitBlocker(self):
        if FileTools.getJavaVersion() == 17:
            from com.xilinx.rapidwright.util import BlockExitSecurityManager
            BlockExitSecurityManager.blockSystemExitCalls()
        with self.assertRaises(SecurityException):
            System.exit(1)
