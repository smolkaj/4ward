/* selector_post_fork_table.p4 — selector fork followed by a dependent table.
 *
 * Tests that the table lookup cache is correctly invalidated after a selector
 * fork. The selector sets meta.tag to different values per member, and a
 * post-fork table matches on meta.tag.  If the cache were incorrectly
 * applied to the post-fork table, all branches would see the same result.
 *
 * Expected trace tree:
 *   fork(selector, 2 members) {
 *     member_0: tag=1 → port=1 (post_table hit)
 *     member_1: tag=2 → port=2 (post_table hit)
 *   }
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

    action set_tag(bit<8> tag) { meta.tag = tag; }
    action set_port(bit<9> port) { smeta.egress_spec = port; }
    action drop() { mark_to_drop(smeta); }

    action_selector(HashAlgorithm.crc16, 32w1024, 32w14) my_selector;

    // Selector table: each member sets a different tag.
    table selector_table {
        key = {
            hdr.ethernet.dstAddr : exact;
            hdr.ethernet.srcAddr : selector;
        }
        actions = { set_tag; drop; }
        implementation = my_selector;
        default_action = drop();
    }

    // Post-fork table: matches on meta.tag (which differs per selector member).
    table post_table {
        key = { meta.tag : exact; }
        actions = { set_port; drop; }
        default_action = drop();
    }

    apply {
        selector_table.apply();
        post_table.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
