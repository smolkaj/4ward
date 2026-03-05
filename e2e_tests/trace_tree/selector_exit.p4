/* selector_exit.p4 — action selector where one member calls exit.
 *
 * The selector table has 2 members.  Member 0 sets egress_spec = 1 normally.
 * Member 1 sets egress_spec = 1 then calls exit.  After the selector table,
 * ingress applies a second table that overwrites egress_spec = 9.  The exit
 * in member 1's branch should skip the second table, so the two branches
 * should produce different traces.
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
                  inout standard_metadata_t smeta) {

    action set_port(bit<9> port) { smeta.egress_spec = port; }
    action set_port_and_exit(bit<9> port) { smeta.egress_spec = port; exit; }
    action drop() { mark_to_drop(smeta); }

    action_selector(HashAlgorithm.crc16, 32w1024, 32w14) sel;

    table selector_table {
        key = {
            hdr.ethernet.dstAddr : exact;
            hdr.ethernet.srcAddr : selector;
        }
        actions = { set_port; set_port_and_exit; drop; }
        implementation = sel;
        default_action = drop();
    }

    action overwrite() { smeta.egress_spec = 9; }
    table after_selector {
        key = { hdr.ethernet.etherType : exact; }
        actions = { overwrite; }
        const entries = { 0x0800 : overwrite(); }
    }

    apply {
        selector_table.apply();
        after_selector.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
