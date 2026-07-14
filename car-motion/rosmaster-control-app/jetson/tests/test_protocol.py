import json
import unittest
from rosmaster_bridge.protocol import ProtocolError, hello, parse_request, response


class ProtocolTests(unittest.TestCase):
    def test_drive_request(self):
        r = parse_request(json.dumps({"op":"drive","id":"d1","command":6,"speed":70,"duration_ms":500}))
        self.assertEqual((r.op,r.command,r.speed,r.duration_ms),("drive",6,70,500))

    def test_every_simple_operation(self):
        for op in ("heartbeat","stop","emergency_stop"):
            with self.subTest(op=op): self.assertEqual(parse_request(json.dumps({"op":op,"id":3})).op,op)

    def test_follow_line_requires_boolean(self):
        self.assertTrue(parse_request(json.dumps({"op":"follow_line","id":1,"enabled":True})).enabled)
        with self.assertRaises(ProtocolError): parse_request(json.dumps({"op":"follow_line","id":1,"enabled":1}))

    def test_rejects_invalid_drive_values(self):
        invalid=({"command":0,"speed":50,"duration_ms":0},{"command":7,"speed":50,"duration_ms":0},{"command":True,"speed":50,"duration_ms":0},{"command":1,"speed":40,"duration_ms":0},{"command":1,"speed":50,"duration_ms":200})
        for values in invalid:
            with self.subTest(values=values), self.assertRaises(ProtocolError): parse_request(json.dumps({"op":"drive","id":"x",**values}))

    def test_rejects_missing_or_invalid_id(self):
        for rid in (None,"",True,[],{}):
            with self.subTest(rid=rid), self.assertRaises(ProtocolError): parse_request(json.dumps({"op":"stop","id":rid}))

    def test_error_preserves_request_id(self):
        with self.assertRaises(ProtocolError) as caught: parse_request(json.dumps({"op":"drive","id":"bad","command":9}))
        self.assertEqual(caught.exception.request_id,"bad")

    def test_hello_and_response_shapes(self):
        state={"serial_ready":True}
        self.assertEqual(hello(state),{"op":"hello","protocol":1,"state":state})
        self.assertEqual(response("a",True,"ok"),{"op":"response","id":"a","ok":True,"message":"ok"})


if __name__ == "__main__": unittest.main()
