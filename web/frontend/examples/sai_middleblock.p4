// sai_middleblock.p4 — Simplified single-file SAI P4 middleblock.
//
// Faithful representation of Google's SAI P4 middleblock pipeline: real table
// names, real control flow structure, real match types. Simplified headers and
// actions to fit in a single file.
//
// See github.com/openconfig/lemern/sai/p4 for the full multi-file version.

#include <core.p4>
#include <v1model.p4>

// ---- Headers ----------------------------------------------------------------

header ethernet_t {
  bit<48> dst_addr;
  bit<48> src_addr;
  bit<16> ether_type;
}

header ipv4_t {
  bit<4>  version;
  bit<4>  ihl;
  bit<6>  dscp;
  bit<2>  ecn;
  bit<16> total_len;
  bit<16> identification;
  bit<3>  flags;
  bit<13> frag_offset;
  bit<8>  ttl;
  bit<8>  protocol;
  bit<16> checksum;
  bit<32> src_addr;
  bit<32> dst_addr;
}

header ipv6_t {
  bit<4>   version;
  bit<6>   dscp;
  bit<2>   ecn;
  bit<20>  flow_label;
  bit<16>  payload_length;
  bit<8>   next_header;
  bit<8>   hop_limit;
  bit<128> src_addr;
  bit<128> dst_addr;
}

struct headers_t {
  ethernet_t ethernet;
  ipv4_t     ipv4;
  ipv6_t     ipv6;
}

struct local_metadata_t {
  bool    bypass_ingress;
  bool    admit_to_l3;
  bit<10> vrf_id;
  bool    wcmp_group_id_valid;
  bit<16> wcmp_group_id;
  bool    nexthop_id_valid;
  bit<16> nexthop_id;
  bool    router_interface_id_valid;
  bit<16> router_interface_id;
  bool    neighbor_id_valid;
  bit<128> neighbor_id;
  bool    marked_to_copy;
  bool    marked_to_mirror;
  bit<16> mirror_session_id;
  bool    acl_drop;
}

// ---- Parser -----------------------------------------------------------------

parser packet_parser(packet_in pkt, out headers_t hdr, inout local_metadata_t meta,
                     inout standard_metadata_t std_meta) {
  state start {
    pkt.extract(hdr.ethernet);
    transition select(hdr.ethernet.ether_type) {
      0x0800: parse_ipv4;
      0x86DD: parse_ipv6;
      default: accept;
    }
  }
  state parse_ipv4 { pkt.extract(hdr.ipv4); transition accept; }
  state parse_ipv6 { pkt.extract(hdr.ipv6); transition accept; }
}

// ---- ACL Pre-Ingress --------------------------------------------------------

control acl_pre_ingress(inout headers_t hdr, inout local_metadata_t meta) {
  action set_vrf(bit<10> vrf_id) { meta.vrf_id = vrf_id; }

  table acl_pre_ingress_table {
    key = {
      hdr.ipv4.isValid()    : optional @name("is_ipv4");
      hdr.ipv6.isValid()    : optional @name("is_ipv6");
      hdr.ethernet.src_addr : ternary  @name("src_mac");
      hdr.ipv4.dst_addr     : ternary  @name("dst_ip");
      hdr.ipv4.dscp         : ternary  @name("dscp");
    }
    actions = { set_vrf; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 256;
  }

  apply { acl_pre_ingress_table.apply(); }
}

// ---- L3 Admit ---------------------------------------------------------------

control l3_admit(inout headers_t hdr, inout local_metadata_t meta,
                 inout standard_metadata_t std_meta) {
  action admit_to_l3() { meta.admit_to_l3 = true; }

  table l3_admit_table {
    key = {
      hdr.ethernet.dst_addr : ternary  @name("dst_mac");
      std_meta.ingress_port : optional @name("in_port");
    }
    actions = { admit_to_l3; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 128;
  }

  apply { l3_admit_table.apply(); }
}

// ---- Routing Lookup ---------------------------------------------------------

control routing_lookup(inout headers_t hdr, inout local_metadata_t meta) {
  action drop() { }
  action set_nexthop_id(bit<16> nexthop_id) {
    meta.nexthop_id = nexthop_id;
    meta.nexthop_id_valid = true;
  }
  action set_wcmp_group_id(bit<16> wcmp_group_id) {
    meta.wcmp_group_id = wcmp_group_id;
    meta.wcmp_group_id_valid = true;
  }
  action set_multicast_group_id(bit<16> multicast_group_id) { }

  table vrf_table {
    key = { meta.vrf_id : exact; }
    actions = { @defaultonly NoAction; }
    const default_action = NoAction;
    size = 64;
  }

  table ipv4_table {
    key = {
      meta.vrf_id     : exact;
      hdr.ipv4.dst_addr : lpm @name("ipv4_dst");
    }
    actions = { drop; set_nexthop_id; set_wcmp_group_id; }
    const default_action = drop;
    size = 4096;
  }

  table ipv6_table {
    key = {
      meta.vrf_id       : exact;
      hdr.ipv6.dst_addr : lpm @name("ipv6_dst");
    }
    actions = { drop; set_nexthop_id; set_wcmp_group_id; }
    const default_action = drop;
    size = 2048;
  }

  table ipv4_multicast_table {
    key = {
      meta.vrf_id       : exact;
      hdr.ipv4.dst_addr : exact @name("ipv4_dst");
    }
    actions = { set_multicast_group_id; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 512;
  }

  table ipv6_multicast_table {
    key = {
      meta.vrf_id       : exact;
      hdr.ipv6.dst_addr : exact @name("ipv6_dst");
    }
    actions = { set_multicast_group_id; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 512;
  }

  apply {
    vrf_table.apply();
    if (hdr.ipv4.isValid() && meta.admit_to_l3) {
      ipv4_table.apply();
    } else if (hdr.ipv4.isValid()) {
      ipv4_multicast_table.apply();
    } else if (hdr.ipv6.isValid() && meta.admit_to_l3) {
      ipv6_table.apply();
    } else if (hdr.ipv6.isValid()) {
      ipv6_multicast_table.apply();
    }
  }
}

// ---- ACL Ingress ------------------------------------------------------------

control acl_ingress(inout headers_t hdr, inout local_metadata_t meta,
                    inout standard_metadata_t std_meta) {
  action acl_copy()    { meta.marked_to_copy = true; }
  action acl_trap()    { meta.marked_to_copy = true; meta.acl_drop = true; }
  action acl_forward() { }
  action acl_mirror(bit<16> mirror_session_id) {
    meta.marked_to_mirror = true;
    meta.mirror_session_id = mirror_session_id;
  }
  action acl_drop()    { meta.acl_drop = true; }

  table acl_ingress_table {
    key = {
      hdr.ipv4.isValid()      : optional @name("is_ipv4");
      hdr.ipv6.isValid()      : optional @name("is_ipv6");
      hdr.ethernet.ether_type : ternary;
      hdr.ethernet.dst_addr   : ternary  @name("dst_mac");
      hdr.ipv4.src_addr       : ternary  @name("src_ip");
      hdr.ipv4.dst_addr       : ternary  @name("dst_ip");
      hdr.ipv4.dscp           : ternary  @name("dscp");
      hdr.ipv4.protocol       : ternary  @name("ip_protocol");
    }
    actions = { acl_copy; acl_trap; acl_forward; acl_mirror; acl_drop;
                @defaultonly NoAction; }
    const default_action = NoAction;
    size = 512;
  }

  table acl_ingress_mirror_and_redirect_table {
    key = {
      std_meta.ingress_port : optional @name("in_port");
      hdr.ipv4.isValid()    : optional @name("is_ipv4");
      hdr.ipv6.isValid()    : optional @name("is_ipv6");
      hdr.ipv4.dst_addr     : ternary  @name("dst_ip");
    }
    actions = { acl_mirror; acl_forward; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 256;
  }

  table acl_ingress_security_table {
    key = {
      hdr.ipv4.isValid()      : optional @name("is_ipv4");
      hdr.ipv6.isValid()      : optional @name("is_ipv6");
      hdr.ethernet.ether_type : ternary;
      hdr.ipv4.src_addr       : ternary  @name("src_ip");
      hdr.ipv4.dst_addr       : ternary  @name("dst_ip");
    }
    actions = { acl_forward; acl_drop; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 256;
  }

  apply {
    acl_ingress_table.apply();
    acl_ingress_mirror_and_redirect_table.apply();
    acl_ingress_security_table.apply();
  }
}

// ---- Routing Resolution -----------------------------------------------------

control routing_resolution(inout headers_t hdr, inout local_metadata_t meta,
                           inout standard_metadata_t std_meta) {
  action set_nexthop_id(bit<16> nexthop_id) {
    meta.nexthop_id = nexthop_id;
    meta.nexthop_id_valid = true;
  }
  action set_ip_nexthop(bit<16> router_interface_id, bit<128> neighbor_id) {
    meta.router_interface_id = router_interface_id;
    meta.router_interface_id_valid = true;
    meta.neighbor_id = neighbor_id;
    meta.neighbor_id_valid = true;
  }
  action set_port_and_src_mac(bit<9> port, bit<48> src_mac) {
    std_meta.egress_spec = port;
    hdr.ethernet.src_addr = src_mac;
  }
  action set_dst_mac(bit<48> dst_mac) {
    hdr.ethernet.dst_addr = dst_mac;
  }

  table wcmp_group_table {
    key = { meta.wcmp_group_id : exact; }
    actions = { set_nexthop_id; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 256;
  }

  table nexthop_table {
    key = { meta.nexthop_id : exact; }
    actions = { set_ip_nexthop; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 1024;
  }

  table router_interface_table {
    key = { meta.router_interface_id : exact; }
    actions = { set_port_and_src_mac; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 256;
  }

  table neighbor_table {
    key = {
      meta.router_interface_id : exact;
      meta.neighbor_id         : exact;
    }
    actions = { set_dst_mac; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 1024;
  }

  apply {
    if (meta.wcmp_group_id_valid) { wcmp_group_table.apply(); }
    if (meta.nexthop_id_valid)    { nexthop_table.apply(); }
    if (meta.router_interface_id_valid) {
      router_interface_table.apply();
      if (meta.neighbor_id_valid) { neighbor_table.apply(); }
    }
  }
}

// ---- Mirror Session Lookup --------------------------------------------------

control mirror_session_lookup(inout local_metadata_t meta,
                              inout standard_metadata_t std_meta) {
  action set_mirror_port(bit<9> port) { std_meta.egress_spec = port; }

  table mirror_session_table {
    key = { meta.mirror_session_id : exact; }
    actions = { set_mirror_port; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 4;
  }

  apply {
    if (meta.marked_to_mirror) { mirror_session_table.apply(); }
  }
}

// ---- ACL Egress -------------------------------------------------------------

control acl_egress(inout headers_t hdr, inout local_metadata_t meta,
                   inout standard_metadata_t std_meta) {
  action acl_drop() { mark_to_drop(std_meta); }

  table acl_egress_table {
    key = {
      hdr.ipv4.protocol     : ternary  @name("ip_protocol");
      std_meta.egress_port  : optional @name("out_port");
      hdr.ipv4.isValid()    : optional @name("is_ipv4");
      hdr.ipv6.isValid()    : optional @name("is_ipv6");
    }
    actions = { acl_drop; @defaultonly NoAction; }
    const default_action = NoAction;
    size = 128;
  }

  apply { acl_egress_table.apply(); }
}

// ---- Main Pipeline ----------------------------------------------------------

control ingress(inout headers_t hdr, inout local_metadata_t meta,
                inout standard_metadata_t std_meta) {
  acl_pre_ingress()    acl_pre_ingress_ctrl;
  l3_admit()           l3_admit_ctrl;
  routing_lookup()     routing_lookup_ctrl;
  acl_ingress()        acl_ingress_ctrl;
  routing_resolution() routing_resolution_ctrl;
  mirror_session_lookup() mirror_session_lookup_ctrl;

  apply {
    if (!meta.bypass_ingress) {
      acl_pre_ingress_ctrl.apply(hdr, meta);
      l3_admit_ctrl.apply(hdr, meta, std_meta);
      routing_lookup_ctrl.apply(hdr, meta);
      acl_ingress_ctrl.apply(hdr, meta, std_meta);
      routing_resolution_ctrl.apply(hdr, meta, std_meta);
      mirror_session_lookup_ctrl.apply(meta, std_meta);
    }
  }
}

control egress(inout headers_t hdr, inout local_metadata_t meta,
               inout standard_metadata_t std_meta) {
  acl_egress() acl_egress_ctrl;
  apply { acl_egress_ctrl.apply(hdr, meta, std_meta); }
}

control verify_ipv4_checksum(inout headers_t hdr, inout local_metadata_t meta) {
  apply { }
}

control compute_ipv4_checksum(inout headers_t hdr, inout local_metadata_t meta) {
  apply { }
}

control deparser(packet_out pkt, in headers_t hdr) {
  apply {
    pkt.emit(hdr.ethernet);
    pkt.emit(hdr.ipv4);
    pkt.emit(hdr.ipv6);
  }
}

V1Switch(packet_parser(), verify_ipv4_checksum(), ingress(), egress(),
         compute_ipv4_checksum(), deparser()) main;
