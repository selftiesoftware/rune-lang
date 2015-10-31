package com.repocad.reposcript.parsing

/**
 * An expression that contains information about an isolated instruction.
 */
trait Expr {
  /**
   * The inherent type of this expression.
   */
  val t: AnyType

}

case class BlockExpr(expr: Seq[Expr]) extends Expr { val t = if (expr.isEmpty) UnitType else expr.last.t }
case class CallExpr(name: String, t : AnyType, params: Seq[Expr]) extends Expr
case class DefExpr(name: String, value : Expr) extends Expr { val t = value.t }
case class FunctionExpr(name : String, params : Seq[RefExpr], body : Expr) extends Expr { val t = body.t }
case class RefExpr(name: String, t : AnyType) extends Expr
case object UnitExpr extends Expr { val t = UnitType }

trait ControlExpr extends Expr
case class ImportExpr(name : String) extends ControlExpr { val t = UnitType }
case class IfExpr(condition : Expr, ifBody : Expr, elseExpr : Expr, t : AnyType) extends ControlExpr
case class LoopExpr(loopCounter : DefExpr, loopEnd : Expr, body : Expr) extends ControlExpr { val t = body.t }

trait ValueExpr[T] extends Expr { val value : T }
case class BooleanExpr(value : Boolean) extends ValueExpr[Boolean] { val t = BooleanType }
case class StringExpr(value : String) extends ValueExpr[String] { val t = StringType }
case class NumberExpr(value : Double) extends ValueExpr[Double] { val t = NumberType }

/**
 * The type from where all data types in RepoScript inherit.
 */
trait AnyType extends Expr {
  val parent : AnyType

  def findCommonParent(that : AnyType): AnyType = {
    if (this.equals(that)) {
      that
    } else if (this.equals(AnyType) || that.equals(AnyType)) {
      AnyType
    } else {
      val thisParent = findCommonParent(that.parent)
      val thatParent = parent.findCommonParent(that)
      if (thisParent != AnyType) {
        thisParent
      } else {
        thatParent
      }
    }
  }

  def isChild(that : AnyType) : Boolean = {
    if (this == AnyType || that.equals(this)) {
      true
    } else if (that == AnyType) {
      false
    } else {
      isChild(that.parent)
    }
  }

}

case object AnyType extends AnyType {
  val t = this
  val parent = AnyType
}

case object BooleanType extends AnyType { val t = this; val parent = AnyType }
case object NumberType extends AnyType { val t = this; val parent = AnyType }
case object StringType extends AnyType { val t = this; val parent = AnyType }
case object UnitType extends AnyType { val t = this; val parent = AnyType }

trait FunctionType extends AnyType {
  val returnType : AnyType
  val parent = AnyType
}

case class Function0Type(returnType : AnyType) extends FunctionType { val t = this; }
case class Function1Type(first : AnyType, returnType : AnyType) extends FunctionType { val t = this }
case class Function2Type(first : AnyType, second : AnyType, returnType : AnyType) extends FunctionType { val t = this }
case class Function3Type(first : AnyType, second : AnyType, third : AnyType, returnType : AnyType)
  extends FunctionType { val t = this }
case class Function4Type(first : AnyType, second : AnyType, third : AnyType, fourth : AnyType, returnType : AnyType)
  extends FunctionType { val t = this }