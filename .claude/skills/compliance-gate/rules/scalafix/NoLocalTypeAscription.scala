/*
rule = NoLocalTypeAscription
 */
package compliancegate.scalafix

import scalafix.v1._

import scala.meta._

class NoLocalTypeAscription extends SyntacticRule("NoLocalTypeAscription") {
  override def fix(implicit doc: SyntacticDocument): Patch = {
    doc.tree.collect {
      case defnVal: Defn.Val if defnVal.decltpe.nonEmpty && isLocal(defnVal) =>
        Patch.lint(
          diagnostic(
            defnVal.decltpe.get,
            "Drop the type ascription on this local val — inference handles it."
          )
        )
      case defnVar: Defn.Var if defnVar.decltpe.nonEmpty && isLocal(defnVar) =>
        Patch.lint(
          diagnostic(
            defnVar.decltpe.get,
            "Drop the type ascription on this local var — inference handles it (and prefer immutable val)."
          )
        )
      case defnDef: Defn.Def if defnDef.decltpe.nonEmpty && isLocal(defnDef) =>
        Patch.lint(
          diagnostic(
            defnDef.decltpe.get,
            "Drop the return-type ascription on this local def — inference handles it. Exception: recursive defs (compiler requires it)."
          )
        )
    }.asPatch
  }

  private def isLocal(tree: Tree): Boolean = {
    tree.parent.exists {
      case block: Term.Block       => !isExtensionGroupBody(block)
      case _: Term.Function        => true
      case _: Term.PartialFunction => true
      case _                       => false
    }
  }

  private def isExtensionGroupBody(block: Term.Block): Boolean =
    block.parent.exists {
      case _: Defn.ExtensionGroup => true
      case _                      => false
    }

  private def diagnostic(decltpe: Type, message: String): Diagnostic =
    Diagnostic(
      id = "no-local-type-ascription",
      message = message,
      position = decltpe.pos
    )
}
