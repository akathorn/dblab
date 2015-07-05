package ch.epfl.data
package dblab.legobase
package frontend

import schema._
import scala.reflect._

object OperatorAST {
  abstract class OperatorNode {
    def toList(): List[OperatorNode] = List(this) ++ (this match {
      case ScanOpNode(_, _, _)                             => List()
      case SelectOpNode(parent, _, _)                      => parent.toList
      case JoinOpNode(leftParent, rightParent, _, _, _, _) => leftParent.toList ++ rightParent.toList
      case AggOpNode(parent, _, _, _)                      => parent.toList
      case MapOpNode(parent, _)                            => parent.toList
      case OrderByNode(parent, ob)                         => parent.toList
      case PrintOpNode(parent, _, _)                       => parent.toList
      case SubqueryNode(parent)                            => parent.toList
      case SubquerySingleResultNode(parent)                => parent.toList
    })
  }
  private var printDepth = 0;
  private def printIdent = " " * (printDepth * 6)

  private def stringify(op: Any, preamble: String = "") = {
    printDepth += 1
    val res = "\n" + printIdent + "|--> " + preamble + op.toString
    printDepth -= 1
    res
  }

  /* Qualifier (if any) is included in the scanOpName */
  case class ScanOpNode(table: Table, scanOpName: String, qualifier: Option[String]) extends OperatorNode {
    override def toString = "ScanOp(" + scanOpName + ")"
  }

  case class SelectOpNode(parent: OperatorNode, cond: Expression, isHavingClause: Boolean) extends OperatorNode {
    override def toString = "SelectOp" + stringify(cond, "CONDITION: ") + stringify(parent)
  }

  case class JoinOpNode(left: OperatorNode, right: OperatorNode, clause: Expression, joinType: JoinType, leftAlias: String, rightAlias: String) extends OperatorNode {
    override def toString = joinType + stringify(left) + stringify(right) + stringify(clause, "CONDITION: ")
  }

  case class AggOpNode(parent: OperatorNode, aggs: Seq[Expression], gb: Seq[(Expression, String)], aggNames: Seq[String]) extends OperatorNode {
    override def toString = "AggregateOp" + stringify(aggs, "AGGREGATES: ") + stringify(gb.map(_._2), "GROUP BY: ") + stringify(parent)
  }

  // TODO -- Currently used by LegoBase only for calculating averages and divisions (thus the / below in toString), but can be generalized
  case class MapOpNode(parent: OperatorNode, mapIndices: Seq[(String, String)]) extends OperatorNode {
    override def toString = "MapOpNode" + stringify(mapIndices.map(mi => mi._1 + " = " + mi._1 + " / " + mi._2).mkString(", ")) + stringify(parent)
  }

  case class OrderByNode(parent: OperatorNode, orderBy: Seq[(Expression, OrderType)]) extends OperatorNode {
    override def toString = {
      val orderByFlds = orderBy.map(ob => ob._1 + " " + ob._2).mkString(", ")
      "SortOp" + stringify(orderByFlds, "ORDER BY: ") + stringify(parent)
    }
  }

  case class PrintOpNode(parent: OperatorNode, projNames: Seq[String], limit: Int) extends OperatorNode {
    override def toString = "PrintOpNode" + stringify(projNames.mkString(","), "PROJ: ") + {
      if (limit != -1) stringify(limit, "LIMIT: ")
      else ""
    } + stringify(parent)
  }

  // Dummy node to know where a subquery begins and ends
  case class SubqueryNode(parent: OperatorNode) extends OperatorNode {
    override def toString = "Subquery" + stringify(parent)
  }

  case class SubquerySingleResultNode(parent: OperatorNode) extends OperatorNode {
    override def toString = "SubquerySingleResult" + stringify(parent)
  }
  case class GetSingleResult(parent: OperatorNode) extends Expression {
    override def toString() = "GetSingleResult(" + stringify(parent) + ")"
  }
}