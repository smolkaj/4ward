// passthrough.p4 — the simplest possible v1model program.
//
// See examples/tutorial.t for a walkthrough.
//
// What it does: extracts an Ethernet header, hardcodes egress port to 1,
// and emits the packet unchanged. No tables, no actions.

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t {
    ethernet_t ethernet;
}

struct metadata_t {}

parser MyParser(packet_in packet, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        packet.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t standard_metadata) {
    apply {
        standard_metadata.egress_spec = 1;
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyDeparser(packet_out packet, in headers_t hdr) {
    apply {
        packet.emit(hdr.ethernet);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
