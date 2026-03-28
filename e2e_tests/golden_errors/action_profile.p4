// action_profile.p4 — v1model program with an action profile.
//
// Used by golden error tests to exercise action profile validation.

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
    // noop is intentionally NOT in profiled_table's action_refs, so it can
    // be used to test "action not valid for profile" error paths.
    action noop() {}

    action_profile(32) my_profile;

    table profiled_table {
        key = { hdr.ethernet.etherType : exact; }
        actions = { forward; drop; }
        implementation = my_profile;
    }

    // Separate table that uses noop, ensuring it appears in p4info.
    table other_table {
        key = { hdr.ethernet.srcAddr : exact; }
        actions = { noop; }
        default_action = noop();
    }

    apply {
        profiled_table.apply();
        other_table.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
