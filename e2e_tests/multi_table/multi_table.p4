/* multi_table.p4 — two tables applied sequentially.
 *
 * An ACL table checks etherType. If the ACL permits the packet (action_run ==
 * permit), a forwarding table routes it; if the ACL drops it, the forwarding
 * table is skipped. Tests that multiple tables run in sequence and that
 * switch on action_run can gate execution of a second table.
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
                inout metadata_t meta, inout standard_metadata_t standard_metadata) {
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
    action permit() {}
    action forward(bit<9> port) { standard_metadata.egress_spec = port; }

    // ACL: block specific etherTypes, permit the rest.
    table acl {
        key = { hdr.ethernet.etherType : exact; }
        actions = { drop; permit; NoAction; }
        default_action = permit();
    }

    // Forwarding: route by destination MAC.
    table fwd {
        key = { hdr.ethernet.dstAddr : exact; }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }

    apply {
        // Run ACL; only apply the forwarding table if not dropped.
        switch (acl.apply().action_run) {
            permit: { fwd.apply(); }
        }
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
