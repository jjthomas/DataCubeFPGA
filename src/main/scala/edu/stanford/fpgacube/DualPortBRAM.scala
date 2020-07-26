package edu.stanford.fpgacube

import chisel3._
import chisel3.util._

// a is read port, b is write port
class DualPortBRAMIO(val dataWidth: Int, val addrWidth: Int) extends Bundle {
  val a_addr = Input(UInt(addrWidth.W))
  val a_dout = Output(UInt(dataWidth.W))
  val b_addr = Input(UInt(addrWidth.W))
  val b_din = Input(UInt(dataWidth.W))
  val b_wr = Input(Bool())
}

class DualPortBRAM(dataWidth: Int, addrWidth: Int) extends Module {
  val io = IO(new DualPortBRAMIO(dataWidth, addrWidth))

  // simulation model for BRAM
  // there's no guarantee about what happens on
  // collisions (sim access to same address with two memory ports)
  val mem = Mem(1 << addrWidth, UInt(dataWidth.W)) // problem with SyncReadMem is that FIRRTL sim
  // doesn't know that there is a register between read port and output so it errors on combinational cycle

  val regAddrA = RegNext(io.a_addr) // ensures write-first behavior on read-write collisions to match Verilog below
  io.a_dout := mem.read(regAddrA)

  when (io.b_wr) {
    mem.write(io.b_addr, io.b_din)
  }
}

class DualPortBRAMBB(dataWidth: Int, addrWidth: Int) extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val io = new DualPortBRAMIO(dataWidth, addrWidth)
  })

  val addrHigh = addrWidth - 1
  val arrayHigh = (1 << addrWidth) - 1
  val dataHigh = dataWidth - 1
  setInline("DualPortBRAMBB.v",
    s"""
       |module DualPortBRAMBB(
       |  input clock,
       |  input [$addrHigh:0] io_a_addr,
       |  output [$dataHigh:0] io_a_dout,
       |  input [$addrHigh:0] io_b_addr,
       |  input [$dataHigh:0] io_b_din,
       |  input io_b_wr
       |);
       |  reg [$dataHigh:0] mem [0:$arrayHigh];
       |  integer i;
       |  initial begin
       |    for (i = 0; i <= $arrayHigh; i = i + 1)
       |      mem[i] = 0;
       |  end
       |  reg [$addrHigh:0] reg_a_addr;
       |  assign io_a_dout = mem[reg_a_addr];
       |  always @(posedge clock) begin
       |    if (io_b_wr) begin
       |      mem[io_b_addr] <= io_b_din;
       |    end
       |    reg_a_addr <= io_a_addr;
       |  end
       |endmodule
     """.stripMargin)
}

object instantiateBRAM {
  def apply(dataWidth: Int, addrWidth: Int, clock: Clock, simulation: Boolean): DualPortBRAMIO = {
    if (simulation) {
      Module(new DualPortBRAM(dataWidth, addrWidth)).io
    } else {
      val bb = Module(new DualPortBRAMBB(dataWidth, addrWidth))
      bb.io.clock := clock
      bb.io.io
    }
  }
}
