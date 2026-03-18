// Minimal program using string port translation.
//
// Uses the SAI forked v1model which declares:
//   @p4runtime_translation("", string) type bit<PORT_BITWIDTH> port_id_t;
// on standard_metadata port fields.
//
// Test fixture for the enriched trace golden test — verifies that trace trees
// show P4Runtime port names ("Ethernet0", "Ethernet1") alongside dataplane
// port numbers.

#include <core.p4>

#define PORT_BITWIDTH 9
#include "v1model_sai.p4"

header ethernet_t {
  bit<48> dst_addr;
  bit<48> src_addr;
  bit<16> ether_type;
}

struct headers_t {
  ethernet_t ethernet;
}

struct metadata_t {}

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
  state start {
    pkt.extract(hdr.ethernet);
    transition accept;
  }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t smeta) {
  action drop() { mark_to_drop(smeta); }
  action forward(port_id_t port) { smeta.egress_spec = port; }

  table forwarding {
    key = { hdr.ethernet.ether_type : exact; }
    actions = { forward; drop; }
    default_action = drop();
  }

  apply { forwarding.apply(); }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
  apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
