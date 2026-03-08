/* clone_preserve_meta.p4 — verify clone_preserving_field_list metadata.
 *
 * Sets a metadata field during ingress, then clones with
 * clone_preserving_field_list. The clone branch writes the metadata
 * value into the packet's etherType so we can observe it in the output.
 *
 * Expected: the clone's etherType reflects the preserved metadata value
 * (0xABCD), while the original packet retains the original etherType.
 */

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }

struct metadata_t {
    @field_list(1)
    bit<16> preserved_value;

    bit<16> not_preserved_value;
}

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
        smeta.egress_spec = 1;
        meta.preserved_value = 16w0xABCD;
        meta.not_preserved_value = 16w0x1234;
        clone_preserving_field_list(CloneType.I2E, 32w100, 8w1);
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) {
    apply {
        // On the clone branch (instance_type == 1), write metadata into
        // the packet so we can observe it in the output.
        if (smeta.instance_type == 1) {
            // preserved_value should be 0xABCD (preserved by field list 1).
            hdr.ethernet.etherType = meta.preserved_value;
            // not_preserved_value should be 0 (NOT in field list 1, reset to default).
            hdr.ethernet.srcAddr[15:0] = meta.not_preserved_value;
        }
    }
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
