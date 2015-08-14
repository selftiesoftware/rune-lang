package com.repocad.reposcript.parsing

import com.repocad.reposcript.lexing.Lexer
import com.repocad.reposcript.util.DirectedGraph
import com.repocad.reposcript.{Environment, HttpClient, parsing}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

trait ParsingTest extends FlatSpec with Matchers with MockFactory with BeforeAndAfter {

  val emptyTypeEnv : TypeEnv = new DirectedGraph(Map(), AnyType)
  val emptyValueEnv : ValueEnv = Map()
  val mockClient = mock[HttpClient]
  var parser : Parser = null

  before {
    parser = new Parser(mockClient, emptyValueEnv, emptyTypeEnv)
  }

  def testEqualsAll(expected : Seq[Expr], expression : String) = {
    parseStringAll(expression).right.map(_._1) should equal(Right(BlockExpr(expected)))
  }

  def testEquals(expected : Expr, expression : String, valueEnv : ValueEnv = Environment.parserValueEnv, typeEnv: TypeEnv = parsing.defaultTypeEnv) = {
    val either = parseString(expression, valueEnv, typeEnv).right.map(_._1)
    either should equal(Right(expected))
  }

  def parseString(string : String, valueEnv : ValueEnv = Environment.parserValueEnv, typeEnv : TypeEnv = parsing.defaultTypeEnv) : Value = {
    val stream = Lexer.lex(string)
    parser.parse(stream, valueEnv, typeEnv, (t, vEnv, tEnv, _) => Right((t, vEnv, tEnv)), f => Left(f))
  }

  def parseStringAll(string : String) = {
    val stream = Lexer.lex(string)
    parser.parse(stream)
  }

}
