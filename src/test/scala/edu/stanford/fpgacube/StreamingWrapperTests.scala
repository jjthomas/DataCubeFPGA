package edu.stanford.fpgacube

import chisel3.iotesters.PeekPokeTester

class StreamingWrapperTests(c: StreamingWrapper, input0: (Int, BigInt), input1: (Int, BigInt),
                            output: (Int, BigInt)) extends PeekPokeTester(c) {
  poke(c.io.inputMemBlockValid, false)
  poke(c.io.outputMemAddrReady, false)
  poke(c.io.outputMemBlockReady, false)

  var inputLeft0 = input0._2
  var inputLeft1 = input1._2 >> c.busWidth // remove length
  var inputBitsLeft = input0._1
  var curInputAddr = 0
  while (inputBitsLeft > 0) {
    poke(c.io.inputMemAddrReady, true)
    while (peek(c.io.inputMemAddrValid).toInt == 0) {
      step(1)
    }
    var inputMemAddr = peek(c.io.inputMemAddr).toInt
    val upperReq = inputMemAddr >= 1000000000
    if (upperReq) {
      inputMemAddr -= 1000000000
    }
    assert(inputMemAddr == curInputAddr)
    val len = peek(c.io.inputMemAddrLen).toInt + 1
    assert(len <= (inputBitsLeft + c.busWidth - 1) / c.busWidth)
    step(1)
    poke(c.io.inputMemAddrReady, false)
    poke(c.io.inputMemBlockValid, true)
    for (i <- 0 until len) {
      poke(c.io.inputMemBlock, (if (upperReq) inputLeft1 else inputLeft0) & ((BigInt(1) << c.busWidth) - 1))
      while (peek(c.io.inputMemBlockReady).toInt == 0) {
        step(1)
      }
      step(1)
      if (upperReq) {
        inputLeft1 >>= c.busWidth
      } else {
        inputLeft0 >>= c.busWidth
      }
      if (upperReq || curInputAddr == 0) { // advance counters for length line or on upper request
        curInputAddr += c.busWidth / 8
        inputBitsLeft -= c.busWidth
      }
    }
    poke(c.io.inputMemBlockValid, false)
  }

  var outputLeft = output._2
  var outputBitsLeft = output._1
  var curOutputAddr = 0
  while (outputBitsLeft > 0) {
    poke(c.io.outputMemAddrReady, true)
    while (peek(c.io.outputMemAddrValid).toInt == 0) {
      step(1)
    }
    assert(peek(c.io.outputMemAddr).toInt == curOutputAddr)
    step(1)
    poke(c.io.outputMemAddrReady, false)
    poke(c.io.outputMemBlockReady, true)
    while (peek(c.io.outputMemBlockValid).toInt == 0) {
      step(1)
    }
    val mask = (BigInt(1) << Math.min(c.busWidth, outputBitsLeft)) - 1
    println("output line: " + (peek(c.io.outputMemBlock) & mask).toString(16))
    assert((peek(c.io.outputMemBlock) & mask) == (outputLeft & mask))
    step(1)
    poke(c.io.outputMemBlockReady, false)

    curOutputAddr += c.busWidth / 8
    outputLeft >>= c.busWidth
    outputBitsLeft -= c.busWidth
  }

  assert(peek(c.io.finished).toInt == 1)
}
