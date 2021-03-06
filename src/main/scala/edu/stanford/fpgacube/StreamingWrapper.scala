package edu.stanford.fpgacube

import chisel3._
import chisel3.util._

class StreamingWrapper(val inputStartAddr: Int, val outputStartAddr: Int, val busWidth: Int, val wordWidth: Int,
                       val numWordsPerGroup: Int, val metricWidth: Int) extends Module {
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
  val outputWordsInLine = busWidth / 64
  var numOutputWords = numFeaturePairs * (1 << (2 * wordWidth))
  // round up to nearest full line
  numOutputWords = (numOutputWords + outputWordsInLine - 1) / outputWordsInLine *
    outputWordsInLine
  val bytesInLine = busWidth / 8

  val inputLengthAddr :: loadInputLength :: mainLoop :: pause :: writeOutput :: finished :: Nil = Enum(6)
  val state = RegInit(inputLengthAddr)
  val inputLength = Reg(UInt(32.W))
  val inputAddrLineCount = RegInit(0.asUInt(32.W))
  val inputDataLineCount = RegInit(0.asUInt(32.W))
  val sendingAddr :: fillingLine :: sendingLine :: Nil = Enum(3)
  val outputState = RegInit(sendingAddr)
  val outputWordCounter = RegInit(0.asUInt(log2Ceil(numOutputWords + 1).W))
  val outputLine = Reg(Vec(outputWordsInLine, UInt(64.W)))


  val featurePairs = new Array[FeaturePair](numFeaturePairs)
  for (i <- 0 until numWordsPerGroup) {
    for (j <- 0 until numWordsPerGroup) {
      val idx = i * numWordsPerGroup + j
      val featurePair = Module(new FeaturePair(wordWidth, metricWidth, idx))
      featurePairs(idx) = featurePair
      featurePair.io.inputMetric := io.inputMemBlock(2 * numWordsPerGroup * wordWidth + metricWidth - 1,
        2 * numWordsPerGroup * wordWidth)
      featurePair.io.inputFeatureOne :=
        io.inputMemBlock((i + 1) * wordWidth - 1, i * wordWidth)
      featurePair.io.inputFeatureTwo :=
        io.inputMemBlock((j + 1 + numWordsPerGroup) * wordWidth - 1,
          (j + numWordsPerGroup) * wordWidth)
      featurePair.io.inputValid := io.inputMemBlockValid && state === mainLoop
      featurePair.io.shiftMode := state === writeOutput
      featurePair.io.doShift := state === writeOutput && outputState === fillingLine
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
      io.inputMemAddr := (inputAddrLineCount << log2Ceil(bytesInLine)).asUInt() +
        (inputStartAddr + bytesInLine).U // final term is start offset of main data stream
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
      switch (outputState) {
        is (sendingAddr) {
          when (io.outputMemAddrReady) {
            outputState := fillingLine
          }
        }
        is (fillingLine) {
          outputLine(outputWordsInLine - 1) := featurePairs(0).io.output
          for (i <- 0 until outputWordsInLine - 1) {
            outputLine(i) := outputLine(i + 1)
          }
          val wordInLine = if (outputWordsInLine == 1) 0.U else
            outputWordCounter(log2Ceil(outputWordsInLine) - 1, 0)
          when (wordInLine === (outputWordsInLine - 1).U) {
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
  chisel3.Driver.execute(args, () => new StreamingWrapper(0, 0, 512,
    4, args(0).toInt, 8))
}
