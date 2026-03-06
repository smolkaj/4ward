// Minimal v1model program compiled against a modified v1model.p4 with 16-bit
// port fields.  Exercises the simulator's ability to derive port-field widths
// from the IR rather than hardcoding bit<9>.

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

parser P(packet_in pkt, out headers_t hdr, inout metadata_t meta,
         inout standard_metadata_t sm) {
    state start {
        pkt.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t sm) {
    action forward(bit<16> port) {
        sm.egress_spec = port;
    }

    action drop() {
        mark_to_drop(sm);
    }

    table port_table {
        key = { hdr.ethernet.etherType : exact; }
        actions = { forward; drop; }
        default_action = drop();
    }

    apply {
        port_table.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t sm) {
    apply {}
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply {
        pkt.emit(hdr.ethernet);
    }
}

V1Switch(P(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(),
         MyDeparser()) main;
