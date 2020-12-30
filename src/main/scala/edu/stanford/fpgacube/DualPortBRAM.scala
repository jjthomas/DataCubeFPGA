package edu.stanford.fpgacube

import chisel3._
import chisel3.util._

// a is read port, b is write port
class DualPortRAMIO(val dataWidth: Int, val addrWidth: Int) extends Bundle {
  val a_addr = Input(UInt(addrWidth.W))
  val a_dout = Output(UInt(dataWidth.W))
  val b_addr = Input(UInt(addrWidth.W))
  val b_din = Input(UInt(dataWidth.W))
  val b_wr = Input(Bool())
}

class DualPortRAMSim(dataWidth: Int, addrWidth: Int) extends Module {
  val io = IO(new DualPortRAMIO(dataWidth, addrWidth))

  val mem = Mem(1 << addrWidth, UInt(dataWidth.W)) // problem with SyncReadMem is that FIRRTL sim
  // doesn't know that there is a register between read port and output so it errors on combinational cycle

  val regAddrA = RegNext(io.a_addr) // ensures write-first behavior on read-write collisions to match Verilog below
  io.a_dout := mem.read(regAddrA)

  when (io.b_wr) {
    mem.write(io.b_addr, io.b_din)
  }
}

object DualPortRAMBB {
  def genVerilog(dataWidth: Int, addrWidth: Int, ramType: String,
                 addrCollision: Boolean, name: String): String = {
    val addrHigh = addrWidth - 1
    val arrayHigh = (1 << addrWidth) - 1
    val dataHigh = dataWidth - 1
    val addrColStr = if (addrCollision) "(* rw_addr_collision= \"yes\" *) " else ""
    s"""
       |module $name(
       |  input clock,
       |  input [$addrHigh:0] io_a_addr,
       |  output reg [$dataHigh:0] io_a_dout,
       |  input [$addrHigh:0] io_b_addr,
       |  input [$dataHigh:0] io_b_din,
       |  input io_b_wr
       |);
       |  (* ram_style = "$ramType" *)
       |  ${addrColStr}reg [$dataHigh:0] mem [0:$arrayHigh];
       |  integer i;
       |  initial begin
       |    for (i = 0; i <= $arrayHigh; i = i + 1)
       |      mem[i] = 0;
       |  end
       |  always @(posedge clock) begin
       |    if (io_b_wr) begin
       |      mem[io_b_addr] <= io_b_din;
       |    end
       |    io_a_dout <= mem[io_a_addr];
       |  end
       |endmodule
     """.stripMargin
  }
}
import DualPortRAMBB._

class DualPortRAMBBIO(val dataWidth: Int, val addrWidth: Int) extends Bundle {
  val clock = Input(Clock())
  val io = new DualPortRAMIO(dataWidth, addrWidth)
}

class DualPortRAMBlock(dataWidth: Int, addrWidth: Int) extends BlackBox with HasBlackBoxInline {
  val io = IO(new DualPortRAMBBIO(dataWidth, addrWidth))
  val modName = "DualPortRAMBlock"
  setInline(s"$modName.v", genVerilog(dataWidth, addrWidth, "block", true, modName))
}

class DualPortRAMUltra(dataWidth: Int, addrWidth: Int) extends BlackBox with HasBlackBoxInline {
  val io = IO(new DualPortRAMBBIO(dataWidth, addrWidth))
  val modName = "DualPortRAMUltra"
  setInline(s"$modName.v", genVerilog(dataWidth, addrWidth, "ultra", false, modName))
}

class DualPortRAMDist(dataWidth: Int, addrWidth: Int) extends BlackBox with HasBlackBoxInline {
  val io = IO(new DualPortRAMBBIO(dataWidth, addrWidth))
  val modName = "DualPortRAMDist"
  setInline(s"$modName.v", genVerilog(dataWidth, addrWidth, "distributed", false, modName))
}

object instantiateRAM {
  def apply(dataWidth: Int, addrWidth: Int, idx: Int, clock: Clock, simulation: Boolean): DualPortRAMIO = {
    if (simulation) {
      Module(new DualPortRAMSim(dataWidth, addrWidth)).io
    } else {
      val bbIo =
        if (idx < 1679) {
          Module(new DualPortRAMBlock(dataWidth, addrWidth)).io
        } else if (idx < 2479) {
          Module(new DualPortRAMUltra(dataWidth, addrWidth)).io
        } else {
          Module(new DualPortRAMDist(dataWidth, addrWidth)).io
        }
      bbIo.clock := clock
      bbIo.io
    }
  }
}
