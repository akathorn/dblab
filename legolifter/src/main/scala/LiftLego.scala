package ch.epfl.data
package legobase
package lifter

import scala.reflect.runtime.universe
import universe.typeOf

import queryengine.volcano._
import ch.epfl.data.autolifter._
import ch.epfl.data.autolifter.annotations._
import ch.epfl.data.autolifter.annotations.Custom._
import scala.collection.mutable.HashMap
import scala.collection.mutable.Set
import scala.collection.mutable.TreeSet
import scala.collection.mutable.DefaultEntry
import java.util.{ Calendar, GregorianCalendar }

object LiftLego {
  val reportToFile = true

  def main(args: Array[String]) {
    implicit val al = new AutoLifter(universe)
    generateLegoBase
    generateNumber
    generateTpe[Array[Any]]
    generateCollection
  }

  val folder = "legocompiler/src/main/scala/ch/epfl/data/legobase/deep"

  def generateNumber(implicit al: AutoLifter) {
    val liftedCodes = List(al.autoLift[Int],
      al.autoLift[Double],
      al.autoLift[java.lang.Character],
      al.autoLift[Long],
      al.autoLift[java.lang.Integer],
      al.autoLift[Boolean])
    val liftedCode = liftedCodes.mkString("\n")
    val file = "DeepScalaNumber"
    printToFile(new java.io.File(s"$folder/scalalib/$file.scala")) { pw =>
      pw.println(s"""/* Generated by AutoLifter © ${new GregorianCalendar().get(Calendar.YEAR)} */

package ch.epfl.data
package legobase
package deep
package scalalib

import pardis.ir._

$liftedCode
""")
    }
  }

  def generateLegoBase(implicit al: AutoLifter) {
    val liftedCodes = List(al.autoLift[SelectOp[Any]],
      al.autoLift[ScanOp[Any]],
      al.autoLift[AggOp[Any, Any]],
      al.autoLift[MapOp[Any]],
      al.autoLift[SortOp[Any]],
      al.autoLift[PrintOp[Any]],
      al.autoLift[queryengine.AGGRecord[Any]],
      al.autoLift[Operator[Any]],
      al.autoLift[storagemanager.TPCHRelations.LINEITEMRecord],
      al.autoLift[storagemanager.K2DBScanner](Custom(component = "DeepDSL", excludedFields = List(CMethod("br"), CMethod("sdf")))))
    val liftedCode = liftedCodes.mkString("\n")
    val file = "DeepLegoBase"
    printToFile(new java.io.File(s"$folder/$file.scala")) { pw =>
      pw.println(s"""/* Generated by AutoLifter © ${new GregorianCalendar().get(Calendar.YEAR)} */

package ch.epfl.data
package legobase
package deep

import scalalib._
import pardis.ir._

$liftedCode
trait DeepDSL extends SelectOpComponent with ScanOpComponent with AggOpComponent with MapOpComponent with SortOpComponent with AGGRecordComponent with OperatorComponent with CharacterComponent with DoubleComponent with IntComponent with LongComponent with ArrayComponent with LINEITEMRecordComponent with K2DBScannerComponent with IntegerComponent with BooleanComponent with HashMapComponent with SetComponent with TreeSetComponent with DefaultEntryComponent with ManualLiftedLegoBase with PrintOpComponent
""")
    }
  }

  def generateCollection(implicit al: AutoLifter) {
    val liftedCodes = List(
      al.autoLift[HashMap[Any, Any]](Custom("DeepDSL", List(CMethod("keySet"), CMethod("getOrElseUpdate"), CMethod("size"), CMethod("remove"), CMethod("clear"), CMethod("<init>"), CMethod("apply"), CMethod("contains"), CMethod("update")))),
      al.autoLift[Set[Any]](Custom("DeepDSL", List(CMethod("apply"), CMethod("toSeq"), CMethod("head"), CMethod("remove")))),
      al.autoLift[TreeSet[Any]](Custom(component = "DeepDSL", includedMethods = List(CMethod("<init>", List(List(), List(CType(typeOf[Ordering[Any]])))), CMethod("+="), CMethod("-="), CMethod("head"), CMethod("size")), excludedMethods = List("rangeImpl"), excludedFields = List(CMethod("treeRef")))),
      al.autoLift[DefaultEntry[Any, Any]](Custom("DeepDSL")))
    val liftedCode = liftedCodes.mkString("\n")
    val file = "DeepScalaCollection"
    printToFile(new java.io.File(s"$folder/scalalib/$file.scala")) { pw =>
      pw.println(s"""/* Generated by AutoLifter © ${new GregorianCalendar().get(Calendar.YEAR)} */

package ch.epfl.data
package legobase
package deep
package scalalib

import pardis.ir._

$liftedCode
""")
    }
  }

  def generateTpe[T: universe.TypeTag](implicit al: AutoLifter) {
    val liftedCode = al.autoLift[T]
    val file = "DeepScala" + implicitly[universe.TypeTag[T]].tpe.typeSymbol.name.toString
    printToFile(new java.io.File(s"$folder/scalalib/$file.scala")) { pw =>
      pw.println(s"""/* Generated by AutoLifter © ${new GregorianCalendar().get(Calendar.YEAR)} */

package ch.epfl.data
package legobase
package deep
package scalalib

import pardis.ir._

$liftedCode
""")
    }
  }

  /* from http://stackoverflow.com/questions/4604237/how-to-write-to-a-file-in-scala */
  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    if (reportToFile) {
      val p = new java.io.PrintWriter(f)
      try { op(p) } finally { p.close() }
    } else {
      val p = new java.io.PrintWriter(System.out)
      try { op(p) } finally { p.flush() }
    }
  }
}

