/*
 * Copyright 2001-2012 Artima, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.scalatest

import org.scalactic._
import org.scalatest.exceptions._

import scala.quoted._
import scala.compiletime.testing.{Error, ErrorKind}

object CompileMacro {

  given FromExpr[ErrorKind] with {
    def unapply(expr: Expr[ErrorKind])(using Quotes) = expr match {
      case '{ ErrorKind.Parser } => Some(ErrorKind.Parser)
      case '{ ErrorKind.Typer }  => Some(ErrorKind.Typer)
      case _ => None
    }
  }

  given FromExpr[Error] with {
    def unapply(expr: Expr[Error])(using Quotes) = expr match {
      case '{ Error(${Expr(msg)}, ${Expr(line)}, ${Expr(col)}, ${Expr(kind)}) } => Some(Error(msg, line, col, kind))
      case _ => None
    }
  }

  // parse and type check a code snippet, generate code to throw TestFailedException when type check passes or parse error
  def assertTypeErrorImpl(self: Expr[_], typeChecked: Expr[List[Error]])(using Quotes): Expr[Assertion] = {

    import quotes.reflect._

    val pos = quotes.reflect.Position.ofMacroExpansion
    val file = pos.sourceFile
    val fileName: String = file.jpath.getFileName.toString
    val filePath: String = org.scalactic.source.Position.filePathnames(file.toString)
    val lineNo: Int = pos.startLine + 1

    def checkNotTypeCheck(code: String): Expr[Assertion] = {
      // For some reason `typeChecked.valueOrError` is failing here, so instead we grab
      // the varargs argument to List.apply and use that to extract the list of errors
      val errors = typeChecked.asTerm.underlyingArgument match {
        case Apply(TypeApply(Select(Ident("List"), "apply"), _), List(seq)) =>
          seq.asExprOf[Seq[Error]].valueOrError.toList
      }

      errors match {
        case Error(_, _, _, ErrorKind.Typer) :: _ => '{ Succeeded }
        case Error(msg, _, _, ErrorKind.Parser) :: _ => '{
          val messageExpr = Resources.expectedTypeErrorButGotParseError(${ Expr(msg) }, ${ Expr(code) })
          throw new TestFailedException((_: StackDepthException) => Some(messageExpr), None, org.scalactic.source.Position(${Expr(fileName)}, ${Expr(filePath)}, ${Expr(lineNo)}))
        }
        case Nil => '{
          val messageExpr = Resources.expectedTypeErrorButGotNone(${ Expr(code) })
          throw new TestFailedException((_: StackDepthException) => Some(messageExpr), None, org.scalactic.source.Position(${Expr(fileName)}, ${Expr(filePath)}, ${Expr(lineNo)}))
        }
      }
    }

    self.asTerm.underlyingArgument match {

      case Literal(StringConstant(code)) =>
        checkNotTypeCheck(code.toString)

      case Apply(Select(_, "stripMargin"), List(Literal(StringConstant(code)))) =>
        checkNotTypeCheck(code.toString.stripMargin)

      case _ =>
        report.throwError("The 'assertTypeError' function only works with String literals.")
    }
  }

  def expectTypeErrorImpl(code: Expr[String], typeChecked: Expr[Boolean], prettifier: Expr[Prettifier], pos: Expr[source.Position])(using Quotes): Expr[Fact] = {
    if (typeChecked.valueOrError)
      '{
          val messageExpr = Resources.expectedTypeErrorButGotNone($code)
          Fact.No(
            messageExpr,
            messageExpr,
            messageExpr,
            messageExpr,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Vector.empty
          )($prettifier)
       }
    else
      '{
          val messageExpr = Resources.gotTypeErrorAsExpected($code)

          Fact.Yes(
            messageExpr,
            messageExpr,
            messageExpr,
            messageExpr,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Vector.empty
          )($prettifier)
       }
  }

  // parse and type check a code snippet, generate code to throw TestFailedException when both parse and type check succeeded
  def assertDoesNotCompileImpl(code: Expr[String], typeChecked: Expr[Boolean])(using Quotes): Expr[Assertion] = {
    val pos = quotes.reflect.Position.ofMacroExpansion
    val file = pos.sourceFile
    val fileName: String = file.jpath.getFileName.toString
    val filePath: String = org.scalactic.source.Position.filePathnames(file.toString)
    val lineNo: Int = pos.startLine + 1
    
    if (!typeChecked.valueOrError) '{ Succeeded }
    else '{
      val messageExpr = Resources.expectedCompileErrorButGotNone($code)
      throw new TestFailedException((_: StackDepthException) => Some(messageExpr), None, org.scalactic.source.Position(${Expr(fileName)}, ${Expr(filePath)}, ${Expr(lineNo)}))
    }
  }

  // parse and type check a code snippet, generate code to return Fact (Yes or No).
  def expectDoesNotCompileImpl(code: Expr[String], typeChecked: Expr[Boolean], prettifier: Expr[Prettifier], pos: Expr[source.Position])(using Quotes): Expr[Fact] = {
    if (typeChecked.valueOrError)
      '{
          val messageExpr = Resources.expectedCompileErrorButGotNone($code)
          Fact.No(
            messageExpr,
            messageExpr,
            messageExpr,
            messageExpr,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Vector.empty
          )($prettifier)
       }
    else
      '{
          val messageExpr = Resources.didNotCompile($code)

          Fact.Yes(
            messageExpr,
            messageExpr,
            messageExpr,
            messageExpr,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Vector.empty
          )($prettifier)
       }
  }

  // parse and type check a code snippet, generate code to throw TestFailedException when either parse or type check fails.
  def assertCompilesImpl(code: Expr[String], typeChecked: Expr[Boolean])(using Quotes): Expr[Assertion] = {
    val pos = quotes.reflect.Position.ofMacroExpansion
    val file = pos.sourceFile
    val fileName: String = file.jpath.getFileName.toString
    val filePath: String = org.scalactic.source.Position.filePathnames(file.toString)
    val lineNo: Int = pos.startLine + 1
    
    if (typeChecked.valueOrError) '{ Succeeded }
    else '{
      val messageExpr = Resources.expectedNoErrorButGotTypeError("unknown", $code)
      throw new TestFailedException((_: StackDepthException) => Some(messageExpr), None, org.scalactic.source.Position(${Expr(fileName)}, ${Expr(filePath)}, ${Expr(lineNo)}))
    }
  }

  def expectCompilesImpl(code: Expr[String], typeChecked: Expr[Boolean], prettifier: Expr[Prettifier], pos: Expr[source.Position])(using Quotes): Expr[Fact] = {
    if (typeChecked.valueOrError)
      '{
          val messageExpr = Resources.compiledSuccessfully($code)
          Fact.Yes(
            messageExpr,
            messageExpr,
            messageExpr,
            messageExpr,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Vector.empty
          )($prettifier)
       }
    else
      '{
          val messageExpr = Resources.expectedNoErrorButGotTypeError("", $code)

          Fact.No(
            messageExpr,
            messageExpr,
            messageExpr,
            messageExpr,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Vector.empty
          )($prettifier)
       }
  }
}
