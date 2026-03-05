/* multicast_selector.p4 — multicast where each replica hits an action selector.
 *
 * Ingress sets mcast_grp = 1 (2 replicas on ports 1, 2).  Egress has an
 * action selector table with 2 members.  The trace tree should fork for
 * multicast first, then within each replica branch, fork again for the
 * action selector — producing 3 levels of nesting.
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
        smeta.mcast_grp = 1;
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) {

    action tag_a() { hdr.ethernet.dstAddr = 0xAAAAAAAAAAAA; }
    action tag_b() { hdr.ethernet.dstAddr = 0xBBBBBBBBBBBB; }
    action noop()  {}

    action_selector(HashAlgorithm.crc16, 32w1024, 32w14) egress_sel;

    table egress_selector {
        key = {
            hdr.ethernet.dstAddr : exact;
            hdr.ethernet.srcAddr : selector;
        }
        actions = { tag_a; tag_b; noop; }
        implementation = egress_sel;
        default_action = noop();
    }

    apply { egress_selector.apply(); }
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
