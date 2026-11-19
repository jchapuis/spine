/*
rule = NoComments
 */
package compliancegate.scalafix

import scalafix.v1._

import scala.meta._

class NoComments extends SyntacticRule("NoComments") {
  override def fix(implicit doc: SyntacticDocument): Patch = {
    val configFieldStarts = doc.tree.collect {
      case configClass: Defn.Class if configClass.name.value.endsWith("Config") =>
        configClass.ctor.paramClauses.flatMap(_.values).map(_.pos.start)
    }.flatten.toSet
    val tokens = doc.tree.tokens
    tokens.zipWithIndex.collect {
      case (comment: Token.Comment, index)
          if !isScalaCliDirective(comment) && !documentsConfigField(comment, index, tokens, configFieldStarts) =>
        Patch.lint(
          Diagnostic(
            id = "no-comments",
            message =
              "No code comments — name things expressively. Scaladoc, block, and line comments are all flagged; only scala-cli directives (`//>`) and Scaladoc on the fields of a `*Config` case class are allowed.",
            position = comment.pos
          )
        )
    }.asPatch
  }

  private def isScalaCliDirective(comment: Token.Comment): Boolean =
    comment.value.startsWith(">")

  private def documentsConfigField(
      comment: Token.Comment,
      index: Int,
      tokens: Tokens,
      configFieldStarts: Set[Int]
  ): Boolean =
    comment.syntax.startsWith("/**") &&
      tokens
        .drop(index + 1)
        .find(token => !token.is[Token.Whitespace])
        .exists(next => configFieldStarts.contains(next.pos.start))
}
