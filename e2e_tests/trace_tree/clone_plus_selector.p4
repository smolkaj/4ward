/* clone_plus_selector.p4 — both clone3 and action selector in the same
 * pipeline, producing mixed fork types.
 *
 * Ingress: clone3, then apply a selector table.
 * The trace tree should fork at the clone point, and within the "original"
 * branch, fork again at the selector table.
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
    action drop() { mark_to_drop(smeta); }

    action_selector(HashAlgorithm.crc16, 32w1024, 32w14) ecmp_selector;

    table ecmp {
        key = {
            hdr.ethernet.dstAddr : exact;
            hdr.ethernet.srcAddr : selector;
        }
        actions = { set_port; drop; }
        implementation = ecmp_selector;
        default_action = drop();
    }

    apply {
        clone(CloneType.I2E, 32w100);
        ecmp.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
