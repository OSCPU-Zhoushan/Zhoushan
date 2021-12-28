import sys

remove = [
  'pht_1_0_MPORT_en',
  'pht_1_0_MPORT_8_en',
  'pht_1_0_MPORT_16_en',
  'pht_1_1_MPORT_1_en',
  'pht_1_1_MPORT_9_en',
  'pht_1_1_MPORT_18_en',
  'pht_1_2_MPORT_2_en',
  'pht_1_2_MPORT_10_en',
  'pht_1_2_MPORT_20_en',
  'pht_1_3_MPORT_3_en',
  'pht_1_3_MPORT_11_en',
  'pht_1_3_MPORT_22_en',
  'pht_1_4_MPORT_4_en',
  'pht_1_4_MPORT_12_en',
  'pht_1_4_MPORT_24_en',
  'pht_1_5_MPORT_5_en',
  'pht_1_5_MPORT_13_en',
  'pht_1_5_MPORT_26_en',
  'pht_1_6_MPORT_6_en',
  'pht_1_6_MPORT_14_en',
  'pht_1_6_MPORT_28_en',
  'pht_1_7_MPORT_7_en',
  'pht_1_7_MPORT_15_en',
  'pht_1_7_MPORT_30_en',
  'pht_0_0_MPORT_17_en',
  'pht_0_1_MPORT_19_en',
  'pht_0_2_MPORT_21_en',
  'pht_0_3_MPORT_23_en',
  'pht_0_4_MPORT_25_en',
  'pht_0_5_MPORT_27_en',
  'pht_0_6_MPORT_29_en',
  'pht_0_7_MPORT_31_en',
  'btb_tag_0_MPORT_en',
  'btb_tag_0_MPORT_16_en',
  'btb_tag_0_MPORT_32_en',
  'btb_tag_1_MPORT_4_en',
  'btb_tag_1_MPORT_20_en',
  'btb_tag_1_MPORT_35_en',
  'btb_tag_2_MPORT_8_en',
  'btb_tag_2_MPORT_24_en',
  'btb_tag_2_MPORT_38_en',
  'btb_tag_3_MPORT_12_en',
  'btb_tag_3_MPORT_28_en',
  'btb_tag_3_MPORT_41_en',
  'btb_target_0_MPORT_1_en',
  'btb_target_0_MPORT_17_en',
  'btb_target_1_MPORT_5_en',
  'btb_target_1_MPORT_21_en',
  'btb_target_2_MPORT_9_en',
  'btb_target_2_MPORT_25_en',
  'btb_target_3_MPORT_13_en',
  'btb_target_3_MPORT_29_en',
  'valid_0_MPORT_3_en',
  'valid_0_MPORT_19_en',
  'valid_0_MPORT_34_en',
  'valid_1_MPORT_7_en',
  'valid_1_MPORT_23_en',
  'valid_1_MPORT_37_en',
  'valid_2_MPORT_11_en',
  'valid_2_MPORT_27_en',
  'valid_2_MPORT_40_en',
  'valid_3_MPORT_15_en',
  'valid_3_MPORT_31_en',
  'valid_3_MPORT_43_en',
  'bht_bht_rdata_0_en',
  'bht_bht_rdata_1_en',
  'bht_bht_wrdata_en',
  'prf_MPORT_3_en',
  'prf_MPORT_4_en',
  'prf_MPORT_5_en',
  'prf_MPORT_6_en',
  'prf_MPORT_7_en',
  'prf_MPORT_8_en',
  'buf_pc_MPORT_2_en',
  'buf_pc_MPORT_3_en',
  'buf_inst_MPORT_2_en',
  'buf_inst_MPORT_3_en',
  'buf_pred_br_MPORT_2_en',
  'buf_pred_br_MPORT_3_en',
  'buf_pred_bpc_MPORT_2_en',
  'buf_pred_bpc_MPORT_3_en',
  'rob_pc_MPORT_2_en',
  'rob_pc_MPORT_3_en',
  'rob_fu_code_MPORT_2_en',
  'rob_fu_code_MPORT_3_en',
  'rob_sys_code_MPORT_2_en',
  'rob_sys_code_MPORT_3_en',
  'rob_rd_addr_MPORT_2_en',
  'rob_rd_addr_MPORT_3_en',
  'rob_rd_en_MPORT_2_en',
  'rob_rd_en_MPORT_3_en',
  'rob_rd_paddr_MPORT_2_en',
  'rob_rd_paddr_MPORT_3_en',
  'rob_rd_ppaddr_MPORT_2_en',
  'rob_rd_ppaddr_MPORT_3_en',
  'tags_rw_r_en',
  'tags_MPORT_1_en',
  '_WIRE_10 = fi_finish;',
  '_T_5 = mie[7]',
  '_T_6 = mtvec[31:2]'
]

replace_name_soc = [
  ['io_master_aw_ready', 'io_master_awready'],
  ['io_master_aw_valid', 'io_master_awvalid'],
  ['io_master_aw_bits_', 'io_master_aw'],
  ['io_master_w_ready', 'io_master_wready'],
  ['io_master_w_valid', 'io_master_wvalid'],
  ['io_master_w_bits_', 'io_master_w'],
  ['io_master_b_ready', 'io_master_bready'],
  ['io_master_b_valid', 'io_master_bvalid'],
  ['io_master_b_bits_', 'io_master_b'],
  ['io_master_ar_ready', 'io_master_arready'],
  ['io_master_ar_valid', 'io_master_arvalid'],
  ['io_master_ar_bits_', 'io_master_ar'],
  ['io_master_r_ready', 'io_master_rready'],
  ['io_master_r_valid', 'io_master_rvalid'],
  ['io_master_r_bits_', 'io_master_r'],
  ['io_slave_aw_ready', 'io_slave_awready'],
  ['io_slave_aw_valid', 'io_slave_awvalid'],
  ['io_slave_aw_bits_', 'io_slave_aw'],
  ['io_slave_w_ready', 'io_slave_wready'],
  ['io_slave_w_valid', 'io_slave_wvalid'],
  ['io_slave_w_bits_', 'io_slave_w'],
  ['io_slave_b_ready', 'io_slave_bready'],
  ['io_slave_b_valid', 'io_slave_bvalid'],
  ['io_slave_b_bits_', 'io_slave_b'],
  ['io_slave_ar_ready', 'io_slave_arready'],
  ['io_slave_ar_valid', 'io_slave_arvalid'],
  ['io_slave_ar_bits_', 'io_slave_ar'],
  ['io_slave_r_ready', 'io_slave_rready'],
  ['io_slave_r_valid', 'io_slave_rvalid'],
  ['io_slave_r_bits_', 'io_slave_r'],
  ['ysyx_000000_RealTop', 'ysyx_000000']
]

replace_name_sim = [
  ['io_memAXI_0_w_bits_data,', 'io_memAXI_0_w_bits_data[3:0],'],
  ['io_memAXI_0_r_bits_data,', 'io_memAXI_0_r_bits_data[3:0],'],
  ['io_memAXI_0_w_bits_data =', 'io_memAXI_0_w_bits_data[0] ='],
  ['io_memAXI_0_r_bits_data;', 'io_memAXI_0_r_bits_data[0];'],
  ['$fwrite', '$fflush; $fwrite']
]

# currently we do not use replace_width array
replace_width = [
  # lsu
  ["[14:0] _T_38 = _GEN_1 << addr_offset", "[7:0] _T_38 = _GEN_1 << addr_offset"],
  ["[126:0] _T_36 = _GEN_0 << _T_35", "[63:0] _T_36 = _GEN_0 << _T_35"],
  ["[14:0] _T_15 = 15'hff << addr_offset", "[7:0] _T_15 = 15'hff << addr_offset"],
  ["[63:0] in1 = io_uop_valid ? _T_3 : r_1", "[31:0] in1 = io_uop_valid ? _T_3[31:0] : r_1[31:0]"],
  ["reg [63:0] r_1", "reg [31:0] r_1"],
  ["[63:0] _T_3 = io_flush ? 64'h0 : io_in1", "[31:0] _T_3 = io_flush ? 32'h0 : io_in1[31:0]"],
  # alu
  ["[126:0] _T_14 = _GEN_0 << shamt", "[63:0] _T_14 = _GEN_0 << shamt"],
  # issue queue
  ["[3:0] _GEN_1835 = io_in_bits_vec_0_valid ? enq_vec_real_1 : enq_vec_real_0", "[2:0] _GEN_1835 = io_in_bits_vec_0_valid ? enq_vec_real_1[2:0] : enq_vec_real_0[2:0]"],
  ["[7:0] rl0 = {ready_list_7,ready_list_6", "[6:0] rl0 = {ready_list_6"],
  ["[3:0] _GEN_2243 = io_in_bits_vec_0_valid ? enq_vec_real_1 : enq_vec_real_0", "[2:0] _GEN_2243 = io_in_bits_vec_0_valid ? enq_vec_real_1[2:0] : enq_vec_real_0[2:0]"],
  ["[7:0] rl1 = rl0 & _T_68", "[6:0] rl1 = rl0[6:0] & _T_68[6:0]"],
  ["[3:0] enq_vec_real_1 = enq_vec_1 - _GEN_2440", "[2:0] enq_vec_real_1 = enq_vec_1 - _GEN_2440"],
  ["[7:0] _T_68 = ~_T_66", "[6:0] _T_68 = ~_T_66[6:0]"],
  ["[3:0] enq_vec_real_1 = enq_vec_1 - _GEN_2848", "[2:0] enq_vec_real_1 = enq_vec_1 - _GEN_2848"],
  ["[7:0] _T_66 = 8'h1 << deq_vec_0", "[6:0] _T_66 = 7'h1 << deq_vec_0"],
  # rename
  ["[63:0] fl1 = fl0 & _T_515", "[62:0] fl1 = fl0[62:0] & _T_515[62:0]"],
  ["[63:0] _T_515 = ~_T_513", "[62:0] _T_515 = ~_T_513[62:0]"],
  ["[63:0] _T_513 = 64'h1 << io_rd_paddr_0", "[62:0] _T_513 = 63'h1 << io_rd_paddr_0"],
  # rob
  ["[4:0] _GEN_289 = io_in_bits_vec_0_valid ? enq_vec_1 : enq_vec_0", "[3:0] _GEN_289 = io_in_bits_vec_0_valid ? enq_vec_1[3:0] : enq_vec_0[3:0]"],
  # ibuf
  ["[3:0] _GEN_12 = io_in_bits_vec_0_valid ? enq_vec_1 : enq_vec_0", "[2:0] _GEN_12 = io_in_bits_vec_0_valid ? enq_vec_1[2:0] : enq_vec_0[2:0]"]
]


if __name__ == '__main__':
  target_soc = False
  if len(sys.argv) > 1:
    if sys.argv[1] == '--soc':
      target_soc = True

  old_file = './build/SimTop.v'
  new_file = './build/SimTopNew.v'
  if target_soc:
    old_file = './build/RealTop.v'
    new_file = './build/ysyx_000000.v'

  f = open(old_file, 'r')
  w = open(new_file, 'w')

  for line in f.readlines():
    if target_soc:
      for signal in remove:
        if signal in line:
          line = '// ' + line
      for signal in replace_name_soc:
        if signal[0] in line:
          line = line.replace(signal[0], signal[1])
    else:
      for signal in replace_name_sim:
        if signal[0] in line:
          line = line.replace(signal[0], signal[1])
    w.write(line)

  f.close()
  w.close()
