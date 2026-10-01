import io
import unittest
import xml.etree.ElementTree as ET
from api34_connected import EXPECTED, validate_xml


class ConnectedResultsTest(unittest.TestCase):
    def stream(self, ids=EXPECTED, failure=False, skipped=False):
        root = ET.Element('testsuite')
        for index, identity in enumerate(ids):
            cls, method = identity.split('#')
            case = ET.SubElement(root, 'testcase', classname='com.hermesagent.mobile.device.' + cls, name=method)
            if index == 1 and failure:
                ET.SubElement(case, 'failure').text = 'windowFocus=false'
            if index == 1 and skipped:
                ET.SubElement(case, 'skipped')
        return io.BytesIO(ET.tostring(root))

    def test_exact_suite(self):
        self.assertTrue(validate_xml([self.stream()])['passed'])

    def test_incidental_method_order(self):
        self.assertTrue(validate_xml([self.stream(list(reversed(EXPECTED)))])['passed'])

    def test_missing(self):
        self.assertFalse(validate_xml([self.stream(EXPECTED[:-1])])['passed'])

    def test_duplicate(self):
        self.assertFalse(validate_xml([self.stream(EXPECTED + EXPECTED[:1])])['passed'])

    def test_failure(self):
        result = validate_xml([self.stream(failure=True)])
        self.assertTrue(result['exact_identity_multiset'])
        self.assertFalse(result['passed'])
        self.assertEqual(result['cases'][1]['failures'], ['windowFocus=false'])

    def test_skipped(self):
        self.assertFalse(validate_xml([self.stream(skipped=True)])['passed'])

    def test_no_report(self):
        self.assertFalse(validate_xml([])['passed'])
