import chisel3._

class Top extends Module {
	val io = IO(new Bundle{
		val a = Input(UInt(64.W))
		val b = Input(UInt(64.W))
		val c = Output(UInt(64.W))
	})
	io.c := io.a + io.b
}
