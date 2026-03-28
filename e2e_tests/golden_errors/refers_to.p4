/* refers_to.p4 — test fixture for @refers_to referential integrity.
 *
 * ref_table's match field has @refers_to("target_table", "id"), requiring that
 * any value inserted in ref_table already exists in target_table's "id" field.
 */

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

    table target_table {
        key = { hdr.ethernet.dstAddr : exact @name("id"); }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }

    table ref_table {
        key = { hdr.ethernet.srcAddr : exact @refers_to("target_table", "id"); }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }

    apply {
        target_table.apply();
        ref_table.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
