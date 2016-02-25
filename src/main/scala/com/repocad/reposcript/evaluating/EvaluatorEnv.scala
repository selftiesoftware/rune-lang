package com.repocad.reposcript.evaluating

import com.repocad.reposcript.parsing.{AnyType, Expr}

/**
  * An environment for the evaluator which discards irrelevant type information to keep the evaluation as optimal as
  * possible.
  */
case class EvaluatorEnv(map: Map[String, Map[Signature, Any]] = Map()) {

  def add(name: String, input: Seq[Expr], output: AnyType, value: Any): EvaluatorEnv = {
    new EvaluatorEnv(map + (name -> map.getOrElse(name, Map()).updated(Signature(input.map(_.t), output), value)))
  }

  def -(name: String, input: Seq[Expr], output: AnyType): EvaluatorEnv = {
    map.get(name).map(_.-(Signature(input.map(_.t), output))) match {
      case Some(empty) if empty.isEmpty => new EvaluatorEnv(map - name)
      case Some(full) => new EvaluatorEnv(map + (name -> full))
      case None => this
    }
  }

  def ++(that: EvaluatorEnv): EvaluatorEnv = {
    new EvaluatorEnv(map ++ that.map)
  }

  def get(name: String, input: Seq[Expr], output: AnyType): Option[Any] = {
    map.get(name).flatMap(_.find(t => t._1 == Signature(input.map(_.t), output)).map(_._2))
  }

}

object EvaluatorEnv {
  def apply() = empty

  val empty = new EvaluatorEnv()
}

case class Signature(input: Seq[AnyType], output: AnyType) {

  override def equals(that: Any): Boolean = {
    that match {
      case second: Signature =>
        output.t.isChild(second.output) && input.zip(second.input).forall(t => t._1.isChild(t._2))
      case _ => false
    }
  }

}

