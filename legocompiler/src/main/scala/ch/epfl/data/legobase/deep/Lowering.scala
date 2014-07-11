package ch.epfl.data
package legobase
package deep

import scala.language.implicitConversions
import pardis.ir._

trait Lowering extends TopDownTransformer[InliningLegoBase, LoweringLegoBase] {
  import from._

  override def transformDef[T: Manifest](node: Def[T]): to.Def[T] = node match {
    case an: AggOpNew[_, _] => {
      val ma = an.manifestA
      val mb = an.manifestB
      val marrDouble = manifest[Array[Double]]
      to.reifyBlock({
        to.__new[AggOp[_, _]](("hm", false, to.__newHashMap(to.overloaded2, mb, marrDouble)),
          ("NullDynamicRecord", false, unit[Any](null)(ma.asInstanceOf[Manifest[Any]])),
          ("keySet", true, to.Set()(mb))).asInstanceOf[to.Rep[T]]
      }).correspondingNode
    }
    case _ => super.transformDef(node)
  }
}

trait LoweringLegoBase extends InliningLegoBase with pardis.ir.InlineFunctions {
}
