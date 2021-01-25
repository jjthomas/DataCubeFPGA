package edu.stanford.fpgacube

import chisel3.iotesters.Driver
import scala.collection.mutable.ArrayBuffer

object Tests {
  val tests = Map(
    "Test1" -> { (backendName: String) =>
      Driver(() => new StreamingWrapper(0, 1000000000, 64, 1,
        1, 32), backendName) {
        (c) => {
          // First input line is the stream length of 1; the next one is a row with
          // the two 1-bit groups in the low two bits and the next 32 bits metric.
          // Output is the 4 (metric, count) tuples in the single feature pair; all are zero
          // except the last which has a metric sum of 1 and a count of 1.
          new StreamingWrapperTests(c, Util.arrToBits(Array(1, 7), 64),
            Util.arrToBits(Array(0, 0, 0, 0, 0, 0, 1, 1), 32))
        }
      }
    }
  )
  def main(args: Array[String]): Unit = {
    val backendName = "verilator"
    val testsToRun = if (args.isEmpty || args.head == "all") {
      tests.keys.toSeq.sorted.toArray
    }
    else {
      args
    }

    var successful = 0
    val errors = new ArrayBuffer[String]
    for (testName <- testsToRun) {
      tests.get(testName) match {
        case Some(test) =>
          println(s"Starting test $testName")
          try {
            if (test(backendName)) {
              successful += 1
            }
            else {
              errors += s"Test $testName: error occurred"
            }
          } catch {
            case exception: Exception =>
              exception.printStackTrace()
              errors += s"Test $testName: exception ${exception.getMessage}"
            case t : Throwable =>
              t.printStackTrace()
              errors += s"Test $testName: throwable ${t.getMessage}"
          }
        case _ =>
          errors += s"Bad test name: $testName"
      }

    }
    if (successful > 0) {
      println(s"Tests passing: $successful")
    }
    if (errors.nonEmpty) {
      println("=" * 80)
      println(s"Errors: ${errors.length}: in the following tests")
      println(errors.mkString("\n"))
      println("=" * 80)
      System.exit(1)
    }
  }
}

