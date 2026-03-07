// basic_table.p4 — simplest table-based v1model program.
//
// Try it:
//   bazel run //cli:4ward -- run examples/basic_table.p4 examples/basic_table.stf
//
// What it does: looks up the Ethernet type in an exact-match table.
// If it matches, forward to the specified port; otherwise, drop.

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }
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
                  inout standard_metadata_t standard_metadata) {

    action drop() { mark_to_drop(standard_metadata); }
    action forward(bit<9> port) { standard_metadata.egress_spec = port; }

    table port_table {
        key = { hdr.ethernet.etherType : exact; }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }

    apply { port_table.apply(); }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
