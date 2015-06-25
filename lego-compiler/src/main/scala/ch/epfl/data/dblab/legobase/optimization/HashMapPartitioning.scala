package ch.epfl.data
package dblab.legobase
package optimization

import schema._
import scala.language.implicitConversions
import sc.pardis.ir._
import reflect.runtime.universe.{ TypeTag, Type }
import sc.pardis.optimization._
import deep._
import sc.pardis.types._
import sc.pardis.types.PardisTypeImplicits._
import sc.pardis.shallow.utils.DefaultValue
import sc.pardis.quasi.anf._
import quasi._
import scala.collection.mutable

/**
 * A transformer for partitioning and indexing the MultiMaps. As a result, this transformation
 * converts a MultiMap and the corresponding operations into an Array (either one dimensional or
 * two dimensional).
 *
 * TODO maybe add an example
 *
 * @param IR the polymorphic embedding trait which contains the reified program.
 */
class HashMapPartitioningTransformer(override val IR: LoweringLegoBase,
                                     val schema: Schema)
  extends RuleBasedTransformer[LoweringLegoBase](IR)
  with StructCollector[LoweringLegoBase] {
  import IR.{ __struct_field => _, _ }

  /**
   * Keeps the information about a relation which (probably) participates in a join.
   * This information is gathered during the analysis phase.
   */
  case class RelationInfo(partitioningField: String,
                          loop: While,
                          array: Rep[Array[Any]]) {
    def tpe: TypeRep[Any] = array.tp.typeArguments(0).asInstanceOf[TypeRep[Any]]
    def numBuckets: Rep[Int] =
      unit(schema.stats.getDistinctAttrValues(partitioningField))
    def bucketSize: Rep[Int] =
      unit(schema.stats.getConflictsAttr(partitioningField).getOrElse(1 << 10))
    def is1D: Boolean =
      schema.findTableByType(tpe).exists(table =>
        table.primaryKey.exists(pk =>
          pk.attributes.size == 1 && pk.attributes.head.name == partitioningField))
    def table: Table = schema.findTableByType(tpe).get
    def reuseOriginal1DArray: Boolean = table.continuous.nonEmpty
    def arraySize: Rep[Int] = array match {
      case dsl"new Array[Any]($s)" => s
    }
  }

  /**
   * Keeps the information about a MultiMap
   */
  case class MultiMapInfo(multiMapSymbol: Rep[Any],
                          leftRelationInfo: Option[RelationInfo] = None,
                          rightRelationInfo: Option[RelationInfo] = None,
                          isPartitioned: Boolean = false,
                          isPotentiallyWindow: Boolean = false,
                          isPotentiallyAnti: Boolean = false,
                          foreachLambda: Option[Lambda[Any, Unit]] = None,
                          collectionForeachLambda: Option[Rep[Any => Unit]] = None) {
    def isOuter: Boolean = getLoweredSymbolOriginalDef(multiMapSymbol) match {
      case Some(loj: LeftOuterJoinOpNew[_, _, _]) => true
      case _                                      => false
    }
    def isAnti: Boolean = isPotentiallyAnti && isPotentiallyWindow
    def shouldBePartitioned: Boolean =
      isPartitioned && (leftRelationInfo.nonEmpty || rightRelationInfo.nonEmpty)
    def partitionedRelationInfo: RelationInfo = (leftRelationInfo, rightRelationInfo) match {
      case _ if isAnti        => rightRelationInfo.get
      case (Some(v), None)    => v
      case (None, Some(v))    => v
      case (Some(l), Some(r)) => l
      case _                  => throw new Exception(s"$this does not have partitioned relation")
    }
    def hasLeft: Boolean =
      leftRelationInfo.nonEmpty
  }

  /**
   * Allows to update and get the associated MultiMapInfo for a MultiMap symbol
   */
  implicit class MultiMapOps[T, S](mm: Rep[MultiMap[T, S]]) {
    private def key = mm.asInstanceOf[Rep[Any]]
    def updateInfo(newInfoFunction: (MultiMapInfo => MultiMapInfo)): Unit =
      multiMapsInfo(key) = newInfoFunction(getInfo)
    def getInfo: MultiMapInfo =
      multiMapsInfo.getOrElseUpdate(key, MultiMapInfo(key))
  }

  /**
   * Data structures for storing the information collected during the analysis phase
   */
  val allMaps = mutable.Set[Rep[Any]]()
  val multiMapsInfo = mutable.Map[Rep[Any], MultiMapInfo]()
  val partitionedMaps = mutable.Set[Rep[Any]]()
  val leftRelationsInfo = mutable.Map[Rep[Any], RelationInfo]()
  val rightRelationsInfo = mutable.Map[Rep[Any], RelationInfo]()
  val hashJoinAntiMaps = mutable.Set[Rep[Any]]()
  val windowOpMaps = mutable.Set[Rep[Any]]()
  val hashJoinAntiForeachLambda = mutable.Map[Rep[Any], Lambda[Any, Unit]]()
  val setForeachLambda = mutable.Map[Rep[Any], Rep[Any => Unit]]()
  val leftOuterJoinDefault = mutable.Map[Rep[Any], Block[Unit]]()
  /* Keeps the closest while loop in the scope */
  var currentWhileLoop: While = _

  // TODO should be removed
  case class HashMapPartitionObject(mapSymbol: Rep[Any], left: Option[PartitionObject], right: Option[PartitionObject]) {
    def partitionedObject: PartitionObject = (left, right) match {
      case _ if isAnti        => right.get
      case (Some(v), None)    => v
      case (None, Some(v))    => v
      case (Some(l), Some(r)) => l
      case _                  => throw new Exception(s"$this doesn't have partitioned object")
    }
    def hasLeft: Boolean =
      left.nonEmpty
    def isAnti: Boolean = hashJoinAntiMaps.contains(mapSymbol)
    def antiLambda: Lambda[Any, Unit] = hashJoinAntiForeachLambda(mapSymbol)
    def multiMapSymbol: Rep[MultiMap[Any, Any]] = mapSymbol.asInstanceOf[Rep[MultiMap[Any, Any]]]
  }

  // TODO should be removed
  case class PartitionObject(relationInfo: RelationInfo) {
    def arr: Rep[Array[Any]] = relationInfo.array
    def fieldFunc: String = relationInfo.partitioningField
    def loopSymbol: While = relationInfo.loop
    def tpe = arr.tp.typeArguments(0).asInstanceOf[TypeRep[Any]]
    def buckets = relationInfo.numBuckets
    def count = partitionedObjectsCount(this)
    def parArr = partitionedObjectsArray(this).asInstanceOf[Rep[Array[Array[Any]]]]
    def is1D: Boolean = relationInfo.is1D
    def table: Table = schema.findTableByType(tpe).get
    def reuseOriginal1DArray: Boolean = table.continuous.nonEmpty
    def arraySize: Rep[Int] = arr match {
      case dsl"new Array[Any]($s)" => s
    }
    def bucketSize: Rep[Int] = relationInfo.bucketSize
  }

  override def optimize[T: TypeRep](node: Block[T]): Block[T] = {
    val res = super.optimize(node)
    System.out.println(s"${scala.Console.GREEN}[$transformedMapsCount] MultiMaps partitioned!${scala.Console.RESET}")
    res
  }

  override def postAnalyseProgram[T: TypeRep](node: Block[T]): Unit = {
    val realHashJoinAntiMaps = hashJoinAntiMaps intersect windowOpMaps
    windowOpMaps --= realHashJoinAntiMaps
    hashJoinAntiMaps.clear()
    hashJoinAntiMaps ++= realHashJoinAntiMaps
    // val supportedMaps = partitionedMaps diff windowOpMaps
    assert(hashJoinAntiMaps.size == multiMapsInfo.map(_._2.isAnti).filter(identity[Boolean]).size)
    assert(multiMapsInfo.filter(_._2.isPartitioned).map(_._1).toSet == partitionedMaps.toSet)

    partitionedHashMapObjects ++= partitionedMaps.map({ mm =>
      val left = leftRelationsInfo.get(mm).map(PartitionObject)
      val right = rightRelationsInfo.get(mm).map(PartitionObject)
      HashMapPartitionObject(mm, left, right)
    })
    // assert(allMaps.filter(shouldBePartitioned).toSet == multiMapsInfo.filter(
    //   mm => mm._2.shouldBePartitioned).map(_._1).toSet)
  }

  /* ---- ANALYSIS PHASE ---- */

  analysis += statement {
    // TODO `new MultiMap` is synthetic and does not exist in shallow
    case sym -> (node @ MultiMapNew()) if node.typeB.isRecord =>
      allMaps += sym
      ()
  }

  analysis += statement {
    case sym -> (node @ dsl"while($cond) $body") =>
      currentWhileLoop = node.asInstanceOf[While]
  }

  analysis += rule {
    case dsl"($mm : MultiMap[Any, Any]).addBinding(__struct_field($struct, ${ Constant(field) }), $nodev)" if allMaps.contains(mm) =>
      partitionedMaps += mm
      mm.updateInfo(_.copy(isPartitioned = true))
      for (array <- getCorrespondingArray(struct)) {
        leftRelationsInfo +=
          mm -> RelationInfo(field, currentWhileLoop, array)
        mm.updateInfo(_.copy(leftRelationInfo =
          Some(RelationInfo(field, currentWhileLoop, array))))
      }
  }

  analysis += rule {
    case dsl"($mm : MultiMap[Any, Any]).get(__struct_field($struct, ${ Constant(field) }))" if allMaps.contains(mm) =>
      partitionedMaps += mm
      mm.updateInfo(_.copy(isPartitioned = true))
      for (array <- getCorrespondingArray(struct)) {
        rightRelationsInfo +=
          mm -> RelationInfo(field, currentWhileLoop, array)
        mm.updateInfo(_.copy(rightRelationInfo =
          Some(RelationInfo(field, currentWhileLoop, array))))
      }
      ()
  }

  analysis += rule {
    case dsl"($mm : MultiMap[Any, Any]).foreach($f)" if allMaps.contains(mm) =>
      windowOpMaps += mm
      mm.updateInfo(_.copy(isPotentiallyWindow = true))
      f match {
        case Def(fun @ Lambda(_, _, _)) =>
          val lambda = fun.asInstanceOf[Lambda[Any, Unit]]
          hashJoinAntiForeachLambda += mm -> lambda
          mm.updateInfo(_.copy(foreachLambda = Some(lambda)))
        case _ => ()
      }
      ()
  }

  analysis += rule {
    case dsl"($mm : MultiMap[Any, Any]).get($elem).get" if allMaps.contains(mm) =>
      // At this phase it's potentially anti hash join multimap, it's not specified for sure
      hashJoinAntiMaps += mm
      mm.updateInfo(_.copy(isPotentiallyAnti = true))
      ()
  }

  analysis += rule {
    case dsl"($mm : MultiMap[Any, Any]).get($elem).get.foreach($f)" => {
      val lambda = f.asInstanceOf[Rep[Any => Unit]]
      setForeachLambda(mm) = lambda
      mm.updateInfo(_.copy(collectionForeachLambda = Some(lambda)))
    }
  }

  analysis += rule {
    // TODO a bit ugly, because of the type inference we have to put `: Any`
    case dsl"""(
                if(($mm: MultiMap[Any, Any]).get($elem).nonEmpty) 
                  $thenp 
                else 
                  $elsep
               ): Any""" if mm.getInfo.isOuter => {
      leftOuterJoinDefault += mm -> elsep.asInstanceOf[Block[Unit]]
      ()
    }
  }

  def getCorrespondingArray(exp: Rep[Any]): Option[Rep[Array[Any]]] = exp match {
    case dsl"($array: Array[Any]).apply($index)" =>
      Some(array)
    case _ =>
      None
  }

  /**
   * Data structures for storing the expressions created during the rewriting phase
   */
  val partitionedHashMapObjects = mutable.Set[HashMapPartitionObject]()
  val partitionedObjectsArray = mutable.Map[PartitionObject, Rep[Array[Any]]]()
  val partitionedObjectsCount = mutable.Map[PartitionObject, Rep[Array[Int]]]()
  val hashJoinAntiRetainVar = mutable.Map[Rep[Any], Var[Boolean]]()
  var loopDepth: Int = 0
  // associates each multimap with a level which specifies the depth of the corresponding for loop
  val fillingHole = mutable.Map[Rep[Any], Int]()
  val fillingFunction = mutable.Map[Rep[Any], () => Rep[Any]]()
  val fillingElem = mutable.Map[Rep[Any], Rep[Any]]()
  val seenArrays = mutable.Set[Rep[Any]]()
  var transformedMapsCount = 0
  val leftOuterJoinExistsVar = mutable.Map[Rep[Any], Var[Boolean]]()

  def getPartitionedObject[T: TypeRep](hm: Rep[T]): HashMapPartitionObject =
    partitionedHashMapObjects.find(x => x.mapSymbol == hm).get

  /* ---- REWRITING PHASE ---- */

  rewrite += statement {
    case sym -> (node @ dsl"new Array[Any]($size)") /*if !seenArrays.contains(sym)*/ =>
      seenArrays += sym
      reflectStm(Stm(sym, node))
      sym
  }

  rewrite += statement {
    case sym -> (node @ MultiMapNew()) if sym.asInstanceOf[Rep[MultiMap[Any, Any]]].getInfo.shouldBePartitioned => {
      val hmParObj = getPartitionedObject(sym)(sym.tp)

      createPartitionArray(hmParObj.partitionedObject)

      sym
    }
  }

  rewrite += remove {
    case dsl"($mm: MultiMap[Any, Any]).get($elem)" if mm.getInfo.shouldBePartitioned => {
      ()
    }
  }

  // The case for HashJoinAnti
  rewrite += rule {
    case dsl"($mm: MultiMap[Any, Any]).addBinding($elem, $nodev)" if mm.getInfo.shouldBePartitioned && mm.getInfo.isAnti =>
      class ElemType
      implicit val elemType = nodev.tp.asInstanceOf[TypeRep[ElemType]]
      val value = apply(nodev).asInstanceOf[Rep[ElemType]]
      val hmParObj = getPartitionedObject(mm)
      val rightArray = hmParObj.partitionedObject
      val key = apply(elem).asInstanceOf[Rep[Int]]
      val antiLambda = hmParObj.antiLambda
      val foreachFunction = antiLambda.body.stmts.collect({ case Statement(sym, SetForeach(_, f)) => f }).head.asInstanceOf[Rep[ElemType => Unit]]
      val resultRetain = __newVarNamed[Boolean](unit(false), "resultRetain")
      hashJoinAntiRetainVar += mm -> resultRetain
      class ElemType2
      implicit val elemType2 = rightArray.arr.tp.typeArguments(0).asInstanceOf[TypeRep[ElemType2]]
      par_array_foreach[ElemType2](rightArray, key, (e: Rep[ElemType2]) => {
        fillingElem(mm) = e
        fillingFunction(mm) = () => apply(nodev)
        fillingHole(mm) = loopDepth
        loopDepth += 1
        val res = inlineBlock2(rightArray.loopSymbol.body)
        fillingHole.remove(mm)
        loopDepth -= 1
        res
      })
      transformedMapsCount += 1
      dsl"""if(${!readVar(resultRetain)}) {
            ${inlineFunction(foreachFunction, value)}
          } else {
          }"""
  }

  rewrite += remove {
    case dsl"($mm: MultiMap[Any, Any]).addBinding($elem, $nodev)" if mm.getInfo.shouldBePartitioned && mm.getInfo.hasLeft && fillingHole.get(mm).isEmpty =>
      ()
  }

  rewrite += rule {
    case dsl"($mm: MultiMap[Any, Any]).addBinding($elem, $nodev)" if mm.getInfo.shouldBePartitioned && mm.getInfo.hasLeft && fillingHole.get(mm).nonEmpty =>
      fillingFunction(mm)()
  }

  rewrite += rule {
    case dsl"($mm: MultiMap[Any, Any]).addBinding($elem, $nodev)" if mm.getInfo.shouldBePartitioned && !mm.getInfo.hasLeft =>
      val hmParObj = getPartitionedObject(mm)
      val leftArray = hmParObj.partitionedObject
      val key = apply(elem).asInstanceOf[Rep[Int]]
      val whileLoop = leftArray.loopSymbol
      class InnerType
      implicit val typeInner = leftArray.tpe.asInstanceOf[TypeRep[InnerType]]
      par_array_foreach[InnerType](leftArray, key, (e: Rep[InnerType]) => {
        fillingElem(mm) = e
        fillingFunction(mm) = () => apply(nodev)
        fillingHole(mm) = loopDepth
        loopDepth += 1
        val res1 = inlineBlock2(whileLoop.body)
        fillingHole.remove(mm)
        loopDepth -= 1
        transformedMapsCount += 1
        res1
      })
  }

  rewrite += rule {
    case dsl"($arr: Array[Any]).apply($index)" if partitionedHashMapObjects.exists(obj =>
      obj.multiMapSymbol.getInfo.shouldBePartitioned &&
        obj.partitionedObject.arr == arr &&
        fillingHole.get(obj.mapSymbol).nonEmpty) =>
      val allObjs = partitionedHashMapObjects.filter(obj =>
        obj.multiMapSymbol.getInfo.shouldBePartitioned &&
          obj.partitionedObject.arr == arr &&
          fillingHole.get(obj.mapSymbol).nonEmpty)
      val sortedObjs = allObjs.toList.sortBy(obj => fillingHole(obj.mapSymbol))
      val hmPartitionedObject = sortedObjs.last
      // System.out.println(s"filling array apply hole with ${fillingElem(hmPartitionedObject.mapSymbol)}: ${hmPartitionedObject.partitionedObject.tpe}, all: ${partitionedHashMapObjects.map(x => x.mapSymbol -> fillingHole.get(x.mapSymbol) -> x.partitionedObject.tpe).mkString("\n")}")
      fillingElem(hmPartitionedObject.mapSymbol)
  }

  rewrite += remove {
    case node @ dsl"while($cond) $body" if partitionedHashMapObjects.exists(obj => obj.multiMapSymbol.getInfo.shouldBePartitioned && obj.partitionedObject.loopSymbol == node) =>
      ()
  }

  rewrite += rule {
    case dsl"($mm: MultiMap[Any, Any]).get($elem).nonEmpty" if mm.getInfo.shouldBePartitioned =>
      unit(true)
  }

  rewrite += remove {
    case dsl"($mm: MultiMap[Any, Any]).get($elem).get" if mm.getInfo.shouldBePartitioned =>
      ()
  }

  rewrite += removeStatement {
    case sym -> Lambda(_, _, _) if setForeachLambda.exists(_._2 == sym) =>
      ()
  }

  rewrite += rule {
    case dsl"($mm: MultiMap[Any, Any]).get($elem).get.foreach($f)" if mm.getInfo.shouldBePartitioned &&
      mm.getInfo.hasLeft => {
      val hmParObj = getPartitionedObject(mm)
      val leftArray = hmParObj.partitionedObject
      val key = apply(elem).asInstanceOf[Rep[Int]]
      val whileLoop = leftArray.loopSymbol
      leftOuterJoinExistsVarDefine(mm)
      class InnerType
      implicit val typeInner = leftArray.tpe.asInstanceOf[TypeRep[InnerType]]
      par_array_foreach[InnerType](leftArray, key, (e: Rep[InnerType]) => {
        fillingElem(mm) = e
        fillingFunction(mm) = () => {
          def ifThenBody: Rep[Unit] = {
            leftOuterJoinExistsVarSet(mm)
            inlineFunction(f.asInstanceOf[Rep[InnerType => Unit]], e)
          }
          dsl"""if(${field[Int](e, leftArray.fieldFunc)} == ${apply(elem)}) {
                    ${ifThenBody}
                  } else {
                  }"""
        }
        // System.out.println(s"STARTED setforeach for the key $key $e.${leftArray.fieldFunc} mm: $mm")
        fillingHole(mm) = loopDepth
        loopDepth += 1
        val res1 = inlineBlock2(whileLoop.body)
        // System.out.println(s"FINISH setforeach for the key $key")
        fillingHole.remove(mm)
        loopDepth -= 1
        transformedMapsCount += 1
        res1
      })
      leftOuterJoinDefaultHandling(mm, key, leftArray)
    }
  }

  rewrite += rule {
    case dsl"($mm: MultiMap[Any, Any]).get($elem).get.exists($p)" if mm.getInfo.shouldBePartitioned &&
      mm.getInfo.hasLeft => {
      val hmParObj = getPartitionedObject(mm)
      val leftArray = hmParObj.partitionedObject
      val key = apply(elem).asInstanceOf[Rep[Int]]
      val whileLoop = leftArray.loopSymbol
      val result = __newVarNamed[Boolean](unit(false), "existsResult")
      class InnerType
      implicit val typeInner = leftArray.tpe.asInstanceOf[TypeRep[InnerType]]
      par_array_foreach[InnerType](leftArray, key, (e: Rep[InnerType]) => {
        fillingElem(mm) = e
        fillingFunction(mm) = () => {
          dsl"""if(${field[Int](e, leftArray.fieldFunc)} == $elem && ${inlineFunction(p, e)}) {
                    ${__assign(result, unit(true))}
                  } else {
                  }"""
        }
        fillingHole(mm) = loopDepth
        loopDepth += 1
        val res1 = inlineBlock2(whileLoop.body)
        fillingHole.remove(mm)
        loopDepth -= 1
        transformedMapsCount += 1
        res1
      })
      readVar(result)
    }
  }

  rewrite += remove {
    case dsl"($mm: MultiMap[Any, Any]).get($elem).get.foreach($f)" if mm.getInfo.shouldBePartitioned &&
      !mm.getInfo.hasLeft &&
      fillingHole.get(mm).isEmpty =>
      ()
  }

  rewrite += remove {
    case dsl"($mm: MultiMap[Any, Any]).foreach($f)" if mm.getInfo.shouldBePartitioned &&
      mm.getInfo.isAnti =>
      ()
  }

  rewrite += rule {
    // TODO a bit ugly, because of the type inference we have to put `: Any`
    case dsl"""(
                if(($mm: MultiMap[Any, Any]).get($elem).nonEmpty) 
                  $thenp 
                else 
                  $elsep
               ): Any""" if mm.getInfo.shouldBePartitioned &&
      mm.getInfo.isAnti &&
      fillingHole.get(mm).nonEmpty =>
      class ElemType
      val retainPredicate = thenp.stmts.collect({
        case Statement(sym, SetRetain(_, p)) => p
      }).head.asInstanceOf[Rep[ElemType => Boolean]]
      val typedElem = fillingFunction(mm)().asInstanceOf[Rep[ElemType]]
      implicit val elemType = typedElem.tp.asInstanceOf[TypeRep[ElemType]]
      val resultRetain = hashJoinAntiRetainVar(mm)
      dsl"""if(!${inlineFunction(retainPredicate, typedElem)}) {
              ${__assign(resultRetain, unit(true))}
            } else {
            }"""
  }

  rewrite += rule {
    case dsl"($mm: MultiMap[Any, Any]).get($elem).get.foreach($f)" if mm.getInfo.shouldBePartitioned &&
      !mm.getInfo.hasLeft &&
      fillingHole.get(mm).nonEmpty =>
      inlineFunction(f, fillingFunction(mm)())
  }

  /* ---- Helper Methods for Rewriting ---- */

  def recreateNode[T: TypeRep](exp: Rep[T]): Rep[T] = exp match {
    case Def(node) => toAtom(node)(exp.tp)
    case _         => ???
  }

  def array_foreach[T: TypeRep](arr: Rep[Array[T]], f: Rep[T] => Rep[Unit]): Rep[Unit] = {
    Range(unit(0), arr.length).foreach {
      __lambda { i =>
        val e = arr(i)
        f(e)
      }
    }
  }

  def par_array_foreach[T: TypeRep](partitionedObject: PartitionObject, key: Rep[Int], f: Rep[T] => Rep[Unit]): Rep[Unit] = {
    if (partitionedObject.is1D) {
      val parArr = partitionedObject.parArr.asInstanceOf[Rep[Array[T]]]
      val bucket = partitionedObject.table.continuous match {
        case Some(continuous) => key - unit(continuous.offset)
        case None             => key
      }
      val e = parArr(bucket)
      // System.out.println(s"part foreach for val $e=$parArr($bucket) ")
      f(e)
    } else {
      val bucket = key % partitionedObject.buckets
      val count = partitionedObject.count(bucket)
      val parArrWhole = partitionedObject.parArr.asInstanceOf[Rep[Array[Array[T]]]]
      val parArr = parArrWhole(bucket)
      Range(unit(0), count).foreach {
        __lambda { i =>
          val e = parArr(i)
          f(e)
        }
      }
    }
  }

  def createPartitionArray(partitionedObject: PartitionObject): Unit = {
    System.out.println(scala.Console.GREEN + "Table " + partitionedObject.arr.tp.typeArguments(0) + " was partitioned on field " + partitionedObject.fieldFunc + scala.Console.RESET)

    class InnerType
    implicit val typeInner = partitionedObject.arr.tp.typeArguments(0).asInstanceOf[TypeRep[InnerType]]
    val originalArray = {
      val origArray =
        if (!seenArrays.contains(partitionedObject.arr)) {
          seenArrays += partitionedObject.arr
          recreateNode(partitionedObject.arr)
        } else {
          partitionedObject.arr
        }
      origArray.asInstanceOf[Rep[Array[InnerType]]]
    }
    val buckets = partitionedObject.buckets
    if (partitionedObject.is1D) {
      //System.out.println(s"${scala.Console.BLUE}1D Array!!!${scala.Console.RESET}")
      if (partitionedObject.reuseOriginal1DArray) {
        partitionedObjectsArray += partitionedObject -> originalArray.asInstanceOf[Rep[Array[Any]]]
      } else {
        val partitionedArray = __newArray[InnerType](buckets)
        partitionedObjectsArray += partitionedObject -> partitionedArray.asInstanceOf[Rep[Array[Any]]]
        array_foreach(originalArray, {
          (e: Rep[InnerType]) =>
            val pkey = field[Int](e, partitionedObject.fieldFunc) % buckets
            partitionedArray(pkey) = e
        })
      }
    } else {
      val partitionedObjectAlreadyExists = {
        partitionedObjectsArray.find({
          case (po, _) =>
            po.fieldFunc == partitionedObject.fieldFunc && po.tpe == partitionedObject.tpe
        })
      }
      if (partitionedObjectAlreadyExists.nonEmpty) {
        System.out.println(s"${scala.Console.BLUE}2D Array already exists!${scala.Console.RESET}")
        partitionedObjectsArray += partitionedObject -> partitionedObjectAlreadyExists.get._1.parArr.asInstanceOf[Rep[Array[Any]]]
        partitionedObjectsCount += partitionedObject -> partitionedObjectAlreadyExists.get._1.count
      } else {
        val partitionedArray = __newArray[Array[InnerType]](buckets)
        val partitionedCount = __newArray[Int](buckets)
        partitionedObjectsArray += partitionedObject -> partitionedArray.asInstanceOf[Rep[Array[Any]]]
        partitionedObjectsCount += partitionedObject -> partitionedCount
        Range(unit(0), buckets).foreach {
          __lambda { i =>
            partitionedArray(i) = __newArray[InnerType](partitionedObject.bucketSize)
          }
        }
        val index = __newVarNamed[Int](unit(0), "partIndex")
        array_foreach(originalArray, {
          (e: Rep[InnerType]) =>
            // TODO needs a better way of computing the index of each object
            val pkey = field[Int](e, partitionedObject.fieldFunc) % buckets
            val currIndex = partitionedCount(pkey)
            val partitionedArrayBucket = partitionedArray(pkey)
            partitionedArrayBucket(currIndex) = e
            partitionedCount(pkey) = currIndex + unit(1)
            __assign(index, readVar(index) + unit(1))
        })
      }
    }
  }

  /* The parts dedicated to left outer join handling */
  def leftOuterJoinDefaultHandling(mm: Rep[MultiMap[Any, Any]], key: Rep[Int], partitionedObject: PartitionObject): Rep[Unit] = if (mm.getInfo.isOuter) {
    dsl"""if(!${readVar(leftOuterJoinExistsVar(mm))}) {
              ${inlineBlock[Unit](leftOuterJoinDefault(mm))}
            } else {
            }"""
  } else dsl"()"

  def leftOuterJoinExistsVarDefine(mm: Rep[MultiMap[Any, Any]]): Unit =
    if (mm.getInfo.isOuter) {
      val exists = __newVarNamed[Boolean](unit(false), "exists")
      leftOuterJoinExistsVar(mm) = exists
      ()
    } else ()

  def leftOuterJoinExistsVarSet(mm: Rep[MultiMap[Any, Any]]): Unit =
    if (mm.getInfo.isOuter) {
      val exists = leftOuterJoinExistsVar(mm)
      __assign(exists, unit(true))
      ()
    } else ()
}
