/* e2e_clone.p4 — E2E clone where egress behavior differs per branch.
 *
 * Ingress sets egress_spec = 2. Egress clones the packet (E2E, session 100)
 * on the first pass (instance_type == 0). Egress reads instance_type: the
 * original (0) tags dstAddr; the clone (CLONE_E2E = 2) tags srcAddr.
 * Verifies both branches execute egress with correct metadata.
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
    apply { smeta.egress_spec = 2; }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) {
    action tag_original() { hdr.ethernet.dstAddr = 0xAAAAAAAAAAAA; }
    action tag_clone()    { hdr.ethernet.srcAddr = 0xBBBBBBBBBBBB; }

    table egress_classify {
        key = { smeta.instance_type : exact; }
        actions = { tag_original; tag_clone; }
        const entries = {
            0 : tag_original();  // original packet
            2 : tag_clone();     // E2E clone (CLONE_E2E instance_type)
        }
    }

    apply {
        // Clone on first pass only.
        if (smeta.instance_type == 0) {
            clone3(CloneType.E2E, 32w100, {});
        }
        egress_classify.apply();
    }
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
