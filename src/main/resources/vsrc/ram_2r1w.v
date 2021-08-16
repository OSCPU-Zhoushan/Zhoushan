/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

// Already defined in ram.v
// import "DPI-C" function void ram_write_helper
// (
//   input  longint    wIdx,
//   input  longint    wdata,
//   input  longint    wmask,
//   input  bit        wen
// );

// import "DPI-C" function longint ram_read_helper
// (
//   input  bit        en,
//   input  longint    rIdx
// );

// ref: https://github.com/OSCPU/ysyx/issues/9
module ram_2r1w (
  input         clk,
  
  input         imem_en,
  input  [63:0] imem_addr,
  output [63:0] imem_data,

  input         dmem_en,
  input  [63:0] dmem_addr,
  output [63:0] dmem_rdata,
  input  [63:0] dmem_wdata,
  input  [63:0] dmem_wmask,
  input         dmem_wen
);

  wire [63:0] imem_data_0 = ram_read_helper(imem_en, {3'b000, (imem_addr - 64'h0000_0000_8000_0000) >> 3});

  assign imem_data = {32'b0000_0000_0000_0000, (imem_addr[2] ? imem_data_0[63:32] : imem_data_0[31:0])};

  assign dmem_rdata = ram_read_helper(dmem_en, {3'b000, (dmem_addr-64'h0000_0000_8000_0000) >> 3});

  always @(posedge clk) begin
    ram_write_helper((dmem_addr - 64'h0000_0000_8000_0000) >> 3, dmem_wdata, dmem_wmask, dmem_en & dmem_wen);
  end

endmodule
