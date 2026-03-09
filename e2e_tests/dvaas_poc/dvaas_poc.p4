#include <core.p4>
#include "v1model.p4"

const bit<9> CPU_PORT = 510;
const bit<9> DROP_PORT = 511;

@controller_header("packet_in")
header packet_in_header_t {
  @id(1) port_id_t ingress_port;
  @id(2) port_id_t target_egress_port;
  @id(3) @padding bit<6> unused_pad;
}

@controller_header("packet_out")
header packet_out_header_t {
  @id(1) port_id_t egress_port;
  @id(2) bit<1> submit_to_ingress;
  @id(3) @padding bit<6> unused_pad;
}

header ethernet_t {
  bit<48> dst_addr;
  bit<48> src_addr;
  bit<16> ether_type;
}

struct headers_t {
  packet_out_header_t packet_out;
  ethernet_t ethernet;
}

struct metadata_t {}

parser ParserImpl(
    packet_in packet,
    out headers_t hdr,
    inout metadata_t meta,
    inout standard_metadata_t standard_metadata) {
  state start {
    transition select((bit<9>) standard_metadata.ingress_port) {
      CPU_PORT: parse_packet_out;
      default: parse_ethernet;
    }
  }

  state parse_packet_out {
    packet.extract(hdr.packet_out);
    transition parse_ethernet;
  }

  state parse_ethernet {
    packet.extract(hdr.ethernet);
    transition accept;
  }
}

control VerifyChecksumImpl(inout headers_t hdr, inout metadata_t meta) {
  apply {}
}

control IngressImpl(
    inout headers_t hdr,
    inout metadata_t meta,
    inout standard_metadata_t standard_metadata) {
  @id(1)
  action set_output_port(@id(1) port_id_t port) {
    standard_metadata.egress_spec = port;
  }

  @id(2)
  action punt_to_controller() {
    standard_metadata.egress_spec = (port_id_t) CPU_PORT;
  }

  @id(3)
  action drop_packet() {
    standard_metadata.egress_spec = (port_id_t) DROP_PORT;
  }

  @id(10)
  table punt_all {
    actions = {
      @proto_id(1) punt_to_controller;
    }
    const default_action = punt_to_controller();
  }

  @id(11)
  table fwd_table {
    key = {
      hdr.ethernet.dst_addr: exact @name("dst_addr") @id(1);
    }
    actions = {
      @proto_id(1) set_output_port;
      @proto_id(2) drop_packet;
    }
    const default_action = drop_packet();
  }

  apply {
    if ((bit<9>) standard_metadata.ingress_port == CPU_PORT && hdr.packet_out.isValid()) {
      if (hdr.packet_out.submit_to_ingress == 0) {
        standard_metadata.egress_spec = hdr.packet_out.egress_port;
        return;
      }
      hdr.packet_out.setInvalid();
    }

    punt_all.apply();
    if ((bit<9>) standard_metadata.egress_spec == 0) {
      fwd_table.apply();
    }
  }
}

control EgressImpl(
    inout headers_t hdr,
    inout metadata_t meta,
    inout standard_metadata_t standard_metadata) {
  apply {}
}

control ComputeChecksumImpl(inout headers_t hdr, inout metadata_t meta) {
  apply {}
}

control DeparserImpl(packet_out packet, in headers_t hdr) {
  apply {
    packet.emit(hdr.ethernet);
  }
}

V1Switch(
    ParserImpl(),
    VerifyChecksumImpl(),
    IngressImpl(),
    EgressImpl(),
    ComputeChecksumImpl(),
    DeparserImpl()) main;
