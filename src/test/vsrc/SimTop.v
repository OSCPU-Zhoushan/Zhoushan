module SimTop (
  input clock,
  input reset,
  input  [63:0] io_logCtrl_log_begin,
  input  [63:0] io_logCtrl_log_end,
  input  [63:0] io_logCtrl_log_level,
  input         io_perfInfo_clean,
  input         io_perfInfo_dump,
  output        io_uart_out_valid,
  output [7:0]  io_uart_out_ch,
  output        io_uart_in_valid,
  input  [7:0]  io_uart_in_ch
);
  ysyxSoCFull soc (
    .clock(clock),
    .reset(reset)
  );
  assign io_uart_out_valid = 1'b0;
  assign io_uart_out_ch = 8'b0;
  assign io_uart_in_valid = 1'b0;
endmodule
