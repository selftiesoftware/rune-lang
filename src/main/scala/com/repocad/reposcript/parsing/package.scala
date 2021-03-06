package com.repocad.reposcript

/**
  * The parsing package contains code for converting [[com.repocad.reposcript.lexing.Token]]s into an Abstract Syntax Tree
  * (AST), which is a composite tree structure with an [[com.repocad.reposcript.parsing.Expr]] as the only root.
  */
package object parsing {

  type Value[T <: ParserState[T]] = Either[ParserError, T]

  type FailureCont[T <: ParserState[T]] = ParserError => Value[T]
  type SuccessCont[T <: ParserState[T]] = T => Value[T]

  type ParserFunction[T <: ParserState[T]] = (T, SuccessCont[T], FailureCont[T]) => Value[T]

}
