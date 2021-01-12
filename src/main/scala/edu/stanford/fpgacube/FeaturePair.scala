package edu.stanford.fpgacube

import chisel3._

class FeaturePair(wordWidth: Int, metricWidth: Int, idx: Int) extends Module {
  val io = IO(new Bundle {
    val inputFeatureOne = Input(UInt(wordWidth.W))
    val inputFeatureTwo = Input(UInt(wordWidth.W))
    val inputMetric = Input(UInt(metricWidth.W))
    val inputValid = Input(Bool())
    val shiftMode = Input(Bool()) // one cycle pause required between last inputValid and start of shiftMode
    val doShift = Input(Bool())
    val neighborOutputIn = Input(UInt(64.W))
    val output = Output(UInt(64.W))
  })

  // count in top 32 bits, metric sum in bottom 32 bits
  val bram = instantiateRAM(64, 2 * wordWidth, clock, false)

  val lastFeatureOne = RegNext(io.inputFeatureOne)
  val lastFeatureTwo = RegNext(io.inputFeatureTwo)
  val lastMetric = RegNext(io.inputMetric)
  val lastInputValid = RegNext(io.inputValid, false.B)
  bram.a_addr := io.inputFeatureOne ## io.inputFeatureTwo
  val readData =
    if (idx >= 800 && idx < 2479) { // BRAM
      val lastWrite = RegNext(bram.b_din)
      val collision = RegNext((bram.a_addr === bram.b_addr) && bram.b_wr)
      Mux(collision, lastWrite, bram.a_dout)
    } else {
      bram.a_dout
    }
  bram.b_din := (readData(63, 32) + 1.U) ## (readData(31, 0) + lastMetric)
  bram.b_addr := lastFeatureOne ## lastFeatureTwo
  bram.b_wr := lastInputValid

  val outputCounter = RegInit(0.asUInt((2 * wordWidth).W))
  when (io.doShift) {
    outputCounter := outputCounter + 1.U // wraps around
  }
  when (io.shiftMode) {
    bram.a_addr := Mux(io.doShift, outputCounter + 1.U, outputCounter)
    bram.b_din := io.neighborOutputIn
    bram.b_addr := outputCounter
    bram.b_wr := io.doShift
  }
  io.output := bram.a_dout
}

object FeaturePair extends App {
  chisel3.Driver.execute(args, () => new FeaturePair(args(0).toInt, args(1).toInt, 0))
}
