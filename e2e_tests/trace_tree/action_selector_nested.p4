/* action_selector_nested.p4 — two selector tables producing nested forks.
 *
 * The ingress applies two tables sequentially, each with an action selector.
 * This should produce a trace tree of depth 2: the first table forks, and
 * within each branch the second table forks again.
 */

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }
struct metadata_t { bit<8> tag; }

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
    action set_tag(bit<8> tag) { meta.tag = tag; }
    action drop() { mark_to_drop(smeta); }

    action_selector(HashAlgorithm.crc16, 32w1024, 32w14) sel1;
    action_selector(HashAlgorithm.crc32, 32w1024, 32w14) sel2;

    table stage1 {
        key = {
            hdr.ethernet.dstAddr : exact;
            hdr.ethernet.srcAddr : selector;
        }
        actions = { set_tag; drop; }
        implementation = sel1;
        default_action = drop();
    }

    table stage2 {
        key = {
            hdr.ethernet.etherType : exact;
            hdr.ethernet.srcAddr : selector;
        }
        actions = { set_port; drop; }
        implementation = sel2;
        default_action = drop();
    }

    apply {
        stage1.apply();
        stage2.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
