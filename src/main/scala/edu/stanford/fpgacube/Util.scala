package edu.stanford.fpgacube

object Util {
  def arrToBits(arr: Array[Int], bitsPerElement: Int): (Int, BigInt) = {
    var buf = BigInt(0)
    for (e <- arr.reverseIterator) {
      buf = (buf << bitsPerElement) | BigInt(e)
    }
    (arr.length * bitsPerElement, buf)
  }
}
