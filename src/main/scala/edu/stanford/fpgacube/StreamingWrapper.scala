package edu.stanford.fpgacube

import chisel3._
import chisel3.util._

class StreamingWrapper(val inputStartAddr: Int, val outputStartAddr: Int, val busWidth: Int, val wordWidth: Int,
                       val numWordsPerGroup: Int, val metricWidth: Int, val simulation: Boolean) extends Module {
  val io = IO(new Bundle {
    val inputMemAddr = Output(UInt(64.W))
    val inputMemAddrValid = Output(Bool())
    val inputMemAddrLen = Output(UInt(8.W))
    val inputMemAddrReady = Input(Bool())
    val inputMemBlock = Input(UInt(busWidth.W))
    val inputMemBlockValid = Input(Bool())
    val inputMemBlockReady = Output(Bool())
    val outputMemAddr = Output(UInt(64.W))
    val outputMemAddrValid = Output(Bool())
    val outputMemAddrLen = Output(UInt(8.W))
    val outputMemAddrId = Output(UInt(16.W))
    val outputMemAddrReady = Input(Bool())
    val outputMemBlock = Output(UInt(busWidth.W))
    val outputMemBlockValid = Output(Bool())
    val outputMemBlockLast = Output(Bool())
    val outputMemBlockReady = Input(Bool())
    val finished = Output(Bool())
  })

  assert(busWidth >= 64)
  val numFeaturePairs = numWordsPerGroup * numWordsPerGroup
  val numOutputWords = numFeaturePairs * (1 << (2 * wordWidth))

  val zeroMems :: inputLengthAddr :: loadInputLength :: mainLoop :: pause :: writeOutput :: finished :: Nil = Enum(7)
  val state = RegInit(if (simulation) zeroMems else inputLengthAddr) // real FPGA RAMs are 0-initialized
  val zeroMemsCounter = RegInit(0.asUInt(log2Ceil(numOutputWords).W))
  val inputLength = Reg(UInt(32.W))
  val inputAddrLineCount = RegInit(0.asUInt(32.W))
  val inputDataLineCount = RegInit(0.asUInt(32.W))
  val sendingAddr :: fillingLine :: sendingLine :: Nil = Enum(3)
  val outputState = RegInit(sendingAddr)
  val outputWordCounter = RegInit(0.asUInt(log2Ceil(numOutputWords + 1).W))
  val outputLine = Reg(Vec(busWidth / 64, UInt(64.W)))


  val featurePairs = new Array[FeaturePair](numFeaturePairs)
  for (i <- 0 until numWordsPerGroup) {
    for (j <- 0 until numWordsPerGroup) {
      val featurePair = Module(new FeaturePair(wordWidth, simulation))
      featurePairs(i * numWordsPerGroup + j) = featurePair
      featurePair.io.inputMetric := io.inputMemBlock(metricWidth - 1, 0)
      featurePair.io.inputFeatureOne :=
        io.inputMemBlock((i + 1) * wordWidth - 1 + metricWidth, i * wordWidth + metricWidth)
      featurePair.io.inputFeatureTwo :=
        io.inputMemBlock((j + 1 + numWordsPerGroup) * wordWidth - 1 + metricWidth,
          (j + numWordsPerGroup) * wordWidth + metricWidth)
      featurePair.io.inputValid := io.inputMemBlockValid && state === mainLoop
      featurePair.io.shiftMode := state === zeroMems
      featurePair.io.doShift := state === zeroMems
    }
  }
  for (i <- 0 until numFeaturePairs) {
    if (i == numFeaturePairs - 1) {
      featurePairs(i).io.neighborOutputIn := 0.U
    } else {
      featurePairs(i).io.neighborOutputIn := featurePairs(i + 1).io.output
    }
  }

  io.inputMemAddr := inputStartAddr.U
  io.inputMemAddrValid := state === inputLengthAddr || (state === mainLoop && inputAddrLineCount =/= inputLength)
  io.inputMemAddrLen := 0.U
  io.inputMemBlockReady := state === loadInputLength || state === mainLoop
  io.outputMemAddr := (outputWordCounter << 3).asUInt() + outputStartAddr.U
  io.outputMemAddrValid := state === writeOutput && outputState === sendingAddr
  io.outputMemAddrLen := 0.U
  io.outputMemAddrId := 0.U
  io.outputMemBlock := outputLine.asUInt()
  io.outputMemBlockValid := state === writeOutput && outputState === sendingLine
  io.outputMemBlockLast := true.B
  io.finished := state === finished
  switch (state) {
    is (zeroMems) {
      when (zeroMemsCounter === (numOutputWords - 1).U) {
        state := inputLengthAddr
      } .otherwise {
        zeroMemsCounter := zeroMemsCounter + 1.U
      }
    }
    is (inputLengthAddr) {
      when (io.inputMemAddrReady) {
        state := loadInputLength
      }
    }
    is (loadInputLength) {
      when (io.inputMemBlockValid) {
        inputLength := io.inputMemBlock
        state := mainLoop
      }
    }
    is (mainLoop) {
      io.inputMemAddr := (inputAddrLineCount << log2Ceil(busWidth / 8)).asUInt() +
        (inputStartAddr + busWidth / 8).U // final term is start offset of main data stream
      val remainingAddrLines = WireInit(inputLength - inputAddrLineCount)
      io.inputMemAddrLen := Mux(remainingAddrLines > 63.U, 63.U, remainingAddrLines - 1.U)
      when (io.inputMemAddrReady) {
        inputAddrLineCount := Mux(remainingAddrLines > 63.U, inputAddrLineCount + 64.U, inputLength)
      }
      when (io.inputMemBlockValid) {
        inputDataLineCount := inputDataLineCount + 1.U
        when (inputDataLineCount === (inputLength - 1.U)) {
          state := pause
        }
      }
    }
    is (pause) {
      // required to flush FeaturePair pipeline before shiftMode is set
      state := writeOutput
    }
    is (writeOutput) {
      for (i <- 0 until numFeaturePairs) {
        featurePairs(i).io.shiftMode := true.B
      }
      switch (outputState) {
        is (sendingAddr) {
          when (io.outputMemAddrReady) {
            outputState := fillingLine
          }
        }
        is (fillingLine) {
          for (i <- 0 until numFeaturePairs) {
            featurePairs(i).io.doShift := true.B
          }
          outputLine(busWidth / 64 - 1) := featurePairs(0).io.output
          for (i <- 0 until busWidth / 64 - 1) {
            outputLine(i) := outputLine(i + 1)
          }
          val wordInLine = if (busWidth == 64) 0.U else outputWordCounter(log2Ceil(busWidth / 64) - 1, 0)
          when (wordInLine === (busWidth / 64 - 1).U || outputWordCounter === (numOutputWords - 1).U) {
            outputState := sendingLine
          }
          outputWordCounter := outputWordCounter + 1.U
        }
        is (sendingLine) {
          when (io.outputMemBlockReady) {
            when (outputWordCounter === numOutputWords.U) {
              state := finished
            } .otherwise {
              outputState := sendingAddr
            }
          }
        }
      }
    }
  }
}

object StreamingWrapper extends App {
  chisel3.Driver.execute(args, () => new StreamingWrapper(0, 1000000000, 512,
    4, 40, 32, false))
}