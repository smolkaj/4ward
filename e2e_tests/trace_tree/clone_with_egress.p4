/* clone_with_egress.p4 — clone where egress behavior differs per branch.
 *
 * Ingress clones the packet (I2E, session 100) and sets egress_spec = 2.
 * Egress reads instance_type: the original packet (instance_type == 0)
 * applies a table that sets dstAddr; the clone (instance_type == 1) applies
 * a different table that sets srcAddr.  This verifies that both branches
 * actually execute egress with the correct metadata.
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
    apply {
        smeta.egress_spec = 2;
        clone3(CloneType.I2E, 32w100, {});
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) {
    action tag_original() { hdr.ethernet.dstAddr = 0xAAAAAAAAAAAA; }
    action tag_clone()    { hdr.ethernet.srcAddr = 0xBBBBBBBBBBBB; }

    table egress_classify {
        key = { smeta.instance_type : exact; }
        actions = { tag_original; tag_clone; }
        const entries = {
            0 : tag_original();
            1 : tag_clone();
        }
    }

    apply { egress_classify.apply(); }
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
