package com.repocad.reposcript.parsing

import com.repocad.reposcript.lexing._
import com.repocad.reposcript.{HttpClient, RemoteCache}

/**
  * Parses code into drawing expressions (AST)
  */
class Parser(val httpClient: HttpClient, val defaultEnv: ParserEnv)
  extends BlockParser with DefinitionParser with ParserInterface {

  val remoteCache = new RemoteCache(httpClient)

  private val DEFAULT_LOOP_COUNTER = "counter"

  private def accumulateExprState(first: ExprState, second: ExprState): ExprState = {
    (first.expr, second.expr) match {
      case (UnitExpr, _) => second
      case (_, UnitExpr) => second.copy(expr = first.expr)
      case (xs: BlockExpr, ys: BlockExpr) => second.copy(expr = BlockExpr(xs.expr ++ ys.expr))
      case (xs: BlockExpr, y) => second.copy(expr = BlockExpr(xs.expr :+ y))
      case (x, ys: BlockExpr) => second.copy(expr = BlockExpr(x +: ys.expr))
      case (x, y) => second.copy(expr = BlockExpr(Seq(x, y)))
    }
  }

  def parse(tokens: LiveStream[Token]): Value[ExprState] = {
    parse(tokens, spillEnvironment = false)
  }

  def parse(tokens: LiveStream[Token], spillEnvironment: Boolean): Value[ExprState] = {
    try {
      val startState = ExprState(UnitExpr, defaultEnv, tokens)
      parseUntil[ExprState](startState, _ => false, accumulateExprState, parse,
        state => {
          if (spillEnvironment) {
            Right(state)
          }
          else {
            Right(state.copy(env = defaultEnv))
          }
        }, e => Left(e))
    } catch {
      case e: InternalError => Left(Error("Script too large (sorry - we're working on it!)", Position.empty))
      case e: Exception => Left(Error(e.getLocalizedMessage, Position.empty))
    }
  }

  override def parse(state: ExprState, successWithoutSuffix: SuccessCont[ExprState],
                     failure: FailureCont[ExprState]): Value[ExprState] = {
    val success: SuccessCont[ExprState] = prefixState => parseSuffix(prefixState, successWithoutSuffix, failure)
    state.tokens match {

      // Import
      case SymbolToken("import") :~: SymbolToken(script) :~: tail =>
        val res = remoteCache.get(script, state.position, code => parse(Lexer.lex(code), spillEnvironment = true))
        res match {
          case Left(error) => failure(error)
          case Right(importState) =>
            success(ExprState(ImportExpr(script), state.env.++(importState.env), tail))
        }

      case SymbolToken("if") :~: tail =>
        parse(state.copy(tokens = tail), conditionState => {
          if (conditionState.expr.t != BooleanType) {
            failure(Error.TYPE_MISMATCH(BooleanType.toString, conditionState.expr.t.toString)(state.position))
          } else {
            parse(conditionState, ifState => {
              ifState.tokens match {
                case SymbolToken("else") :~: elseIfTail =>
                  parse(ifState.copy(tokens = elseIfTail), elseState => {
                    success(ExprState(IfExpr(conditionState.expr, ifState.expr,
                      elseState.expr, ifState.expr.t.findCommonParent(elseState.expr.t)),
                      state.env, elseState.tokens))
                  }, failure)
                case _ => success(ExprState(IfExpr(conditionState.expr, ifState.expr,
                  UnitExpr, ifState.expr.t.findCommonParent(UnitType)), state.env, ifState.tokens))
              }
            }, failure)
          }
        }, failure)

      // Loops
      case SymbolToken("repeat") :~: tail => parseLoop(state.copy(tokens = tail), success, failure)

      // Functions and objects
      case SymbolToken("def") :~: tail => parseDefinition(state.copy(tokens = tail), success, failure)

      // Blocks
      case PunctToken("{") :~: tail =>
        parseUntilToken[ExprState](state.copy(expr = UnitExpr, tokens = tail), "}", accumulateExprState, parse, success, failure)
      case PunctToken("(") :~: tail =>
        parseUntilToken[ExprState](state.copy(expr = UnitExpr, tokens = tail), ")", accumulateExprState, parse, success, failure)

      // Calls to functions or objects
      case SymbolToken(name) :~: PunctToken("(") :~: tail =>
        def parseCall(originalParameters: Seq[RefExpr], t: AnyType, errorFunction: String => Error): Value[ExprState] = {
          parseUntilToken[ExprState](state.copy(expr = UnitExpr, tokens = tail), ")", accumulateExprState, parse, (parameterState: ExprState) => {
            val parameters: Seq[Expr] = parameterState.expr match {
              case BlockExpr(params) => params
              case UnitExpr => Seq()
              case expr => Seq(expr)
            }
            if (verifySameParams(originalParameters, parameters)) {
              success(ExprState(CallExpr(name, t, parameters), state.env, parameterState.tokens))
            } else {
              failure(errorFunction(parameters.toString))
            }
          }, failure)
        }
        state.env.getAsType(name, _.isInstanceOf[FunctionType]) match {
          case Right(function: FunctionType) =>
            parseCall(function.params, function.returnType, Error.EXPECTED_FUNCTION_PARAMETERS(name, function.params.toString, _)(state.position))
          case Right(ref: RefExpr) =>
            ref.t match {
              case function: FunctionType => parseCall(function.params, function.returnType, Error.EXPECTED_FUNCTION_PARAMETERS(name, function.params.toString, _)(state.position))
              case notFunction => failure(Error.TYPE_MISMATCH("function", notFunction.toString, "calling " + name)(state.position))
            }
          case x => // Some(RefExpr) could be returned, so None does not cover this case entirely
            state.env.getAsType(name, _.isInstanceOf[ObjectType]) match {
              case Right(o: ObjectType) =>
                parseCall(o.params, o, Error.EXPECTED_OBJECT_PARAMETERS(name, o.params.toString, _)(state.position))
              case Left(_) => // Assume regular reference
                parseReference(name, tail, state, success, failure)
            }
        }

      // Values
      case BooleanToken(value: Boolean) :~: tail => success(ExprState(BooleanExpr(value), state.env, tail))
      case SymbolToken("false") :~: tail => success(ExprState(BooleanExpr(false), state.env, tail))
      case SymbolToken("true") :~: tail => success(ExprState(BooleanExpr(true), state.env, tail))
      case DoubleToken(value: Double) :~: tail => success(ExprState(NumberExpr(value), state.env, tail))
      case IntToken(value: Int) :~: tail => success(ExprState(NumberExpr(value), state.env, tail))
      case StringToken(value: String) :~: tail => success(ExprState(StringExpr(value), state.env, tail))

      // References to values, functions or objects
      case SymbolToken(name) :~: tail => parseReference(name, tail, state, success, failure)

      case rest if rest.isEmpty => success(state.copy(UnitExpr, tokens = rest))

      case xs => failure(Error(s"Unrecognised token pattern $xs", xs.head.position))
    }
  }


  private def parseLoop(state: ExprState, success: SuccessCont[ExprState],
                        failure: FailureCont[ExprState]): Value[ExprState] = {
    def parseLoopWithRange(counterName: String, from: Expr, to: Expr, bodyTokens: LiveStream[Token],
                           success: SuccessCont[ExprState], failure: FailureCont[ExprState]): Value[ExprState] = {
      if (!NumberType.isChild(from.t)) {
        failure(Error.TYPE_MISMATCH("number", from.t.toString, "defining the number to start from in a loop")(state.position))
      } else if (!NumberType.isChild(to.t)) {
        failure(Error.TYPE_MISMATCH("number", to.t.toString, "defining when to end a loop")(state.position))
      } else {
        parse(ExprState(UnitExpr, state.env + (counterName -> from), bodyTokens), bodyState => {
          success(ExprState(LoopExpr(DefExpr(counterName, from), to, bodyState.expr),
            state.env, bodyState.tokens))
        }, failure)
      }
    }
    def parseLoopWithCounterName(fromExpr: Expr, toExpr: Expr, loopNameTail: LiveStream[Token]) = {
      loopNameTail match {
        case SymbolToken(counterName) :~: tail => parseLoopWithRange(counterName, fromExpr, toExpr, tail, success, failure)
        case unknown => failure(Error.SYNTAX_ERROR("name of a loop variable", unknown.toString)(state.position))
      }
    }
    parse(state, firstState => firstState.tokens match {
      case SymbolToken("to") :~: toTail => parse(firstState.copy(tokens = toTail), toState => toState.tokens match {
        case SymbolToken("using") :~: secondTail => parseLoopWithCounterName(firstState.expr, toState.expr, secondTail)
        case _ => parseLoopWithRange(DEFAULT_LOOP_COUNTER, firstState.expr, toState.expr, toState.tokens, success, failure)
      }, failure)
      case SymbolToken("using") :~: usingTail => parseLoopWithCounterName(NumberExpr(1), firstState.expr, usingTail)
      case _ => parseLoopWithRange(DEFAULT_LOOP_COUNTER, NumberExpr(1), firstState.expr, firstState.tokens, success, failure)
    }, failure)
  }

  private def parseReference(name: String, tail: LiveStream[Token], state: ParserState[_],
                             success: SuccessCont[ExprState], failure: FailureCont[ExprState]): Value[ExprState] = {
    state.env.get(name) match {
      case Right(typeExpr: AnyType) => success(ExprState(RefExpr(name, typeExpr), state.env, tail))
      case Right(expr) => success(ExprState(RefExpr(name, expr.t), state.env, tail))
      case Left(errorFunction) => failure(errorFunction(state.position))
    }
  }

  private def parseSuffix(state: ExprState, success: SuccessCont[ExprState],
                          failure: FailureCont[ExprState]): Value[ExprState] = {
    state.tokens match {
      // Object field accessors
      case PunctToken(".") :~: (accessor: SymbolToken) :~: tail =>
        def findParamFromObject(reference: Expr, obj: ObjectType): Value[ExprState] = {
          obj.params.find(_.name == accessor.s).map(
            param => success(ExprState(RefFieldExpr(reference, param.name, param.t), state.env, tail))
          ).getOrElse(failure(Error.OBJECT_UNKNOWN_PARAMETER_NAME(obj.name, accessor.s)(accessor.position)))
        }

        state.expr match {
          case call: CallExpr if call.t.isInstanceOf[ObjectType] => findParamFromObject(call, call.t.asInstanceOf[ObjectType])
          case ref: RefExpr if ref.t.isInstanceOf[ObjectType] => findParamFromObject(ref, ref.t.asInstanceOf[ObjectType])

          case unknown => failure(Error.EXPECTED_OBJECT_ACCESS(state.expr.toString)(state.position))
        }

      case PunctToken(name) :~: tail if !"{}()".contains(name) => parseSuffixFunction(name, state.copy(tokens = tail), success, failure)
      case SymbolToken(name) :~: tail => parseSuffixFunction(name, state, success, failure)

      case _ => success(state)
    }
  }

  def parseSuffixFunction(name: String, state: ExprState, success: SuccessCont[ExprState],
                          failure: FailureCont[ExprState]): Value[ExprState] = {
    def parseAsFunction(f: SuccessCont[ExprState]): Value[ExprState] =
      parse(ExprState(UnitExpr, state.env, state.tokens.tail /* Exclude the last token (the name) */), f, failure)

    state.env.getAll(name).filter(_.isInstanceOf[FunctionType]).toSeq match {
      case Seq(f: FunctionType) if f.params.size == 1 => parseAsFunction(firstState => {
        // Should be parsed as normal
        success(state)
      })
      // If the call requires two parameters, we look to the previous expression
      case Seq(f: FunctionType) if f.params.size == 2 =>
        state.expr match {
          case firstParameter: Expr if f.params.head.t.isChild(firstParameter.t) =>
            parseAsFunction(secondState => {
              val secondParameter = secondState.expr
              if (f.params(1).t.isChild(secondParameter.t)) {
                state.expr match {
                  case DefExpr(defName, value) =>
                    success(secondState.copy(expr = DefExpr(defName, CallExpr(name, f.returnType, Seq(value, secondParameter)))))
                  case expr =>
                    success(ExprState(CallExpr(name, f.returnType, Seq(firstParameter, secondParameter)), state.env, secondState.tokens))
                }
              } else {
                failure(Error.TYPE_MISMATCH(f.params.head.t.toString, firstParameter.t.toString)(secondState.position))
              }
            })
          case _ => success(state)
        }

      case _ => success(state)
    }

  }

  private def verifySameParams(parameters: Seq[RefExpr], callParameters: Seq[Expr]): Boolean = {
    if (parameters.size != callParameters.size) {
      return false
    }

    for (i <- parameters.indices) {
      if (!parameters(i).t.isChild(callParameters(i).t)) {
        return false
      }
    }
    true
  }

}