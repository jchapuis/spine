/*
rule = NoComments
 */
package compliancegate.scalafix

import scalafix.v1._

import scala.meta._

class NoComments extends SyntacticRule("NoComments") {
  override def fix(implicit doc: SyntacticDocument): Patch = {
    doc.tree.tokens.collect {
      case comment: Token.Comment if !isScalaCliDirective(comment) =>
        Patch.lint(
          Diagnostic(
            id = "no-comments",
            message =
              "No code comments — name things expressively. Scaladoc, block, and line comments are all flagged; only scala-cli directives (`//>`) are allowed.",
            position = comment.pos
          )
        )
    }.asPatch
  }

  private def isScalaCliDirective(comment: Token.Comment): Boolean =
    comment.value.startsWith(">")
}
