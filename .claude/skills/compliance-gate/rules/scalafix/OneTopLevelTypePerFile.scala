/*
rule = OneTopLevelTypePerFile
 */
package compliancegate.scalafix

import scalafix.v1._

import scala.meta._

class OneTopLevelTypePerFile extends SyntacticRule("OneTopLevelTypePerFile") {
  override def fix(implicit doc: SyntacticDocument): Patch = {
    val stats = topLevelStats(doc.tree)
    val constraintAliases = refinementConstraintNames(stats)
    val declarations = stats.flatMap(typeDeclaration).filterNot(isConstraintAlias(_, constraintAliases))
    val distinctTypes = declarations
      .groupBy(_.name)
      .values
      .map(group => group.minBy(_.tree.pos.start))
      .toList
      .sortBy(_.tree.pos.start)
    val stem = fileStem(doc.input)
    distinctTypes match {
      case Nil | _ :: Nil => Patch.empty
      case all =>
        val principal = all.find(declaration => stem.contains(declaration.name)).getOrElse(all.head)
        all.filterNot(_ eq principal).map { extra =>
          Patch.lint(
            Diagnostic(
              id = "one-top-level-type-per-file",
              message =
                s"Top-level ${extra.kind} `${extra.name}` shares this file with `${principal.name}` — move it to its own `${extra.name}.scala` (one top-level type per file; only a companion object, nested ADT cases and the iron `type X = X.T` + `object X` pair may share it).",
              position = extra.tree.pos
            )
          )
        }.asPatch
    }
  }

  private case class TypeDeclaration(name: String, kind: String, tree: Tree)

  private def topLevelStats(tree: Tree): List[Stat] = tree match {
    case source: Source => source.stats.flatMap(topLevelStatsOf)
    case _              => List.empty
  }

  private def topLevelStatsOf(stat: Stat): List[Stat] = stat match {
    case pkg: Pkg => pkg.stats.flatMap(topLevelStatsOf)
    case other    => List(other)
  }

  private def typeDeclaration(stat: Stat): Option[TypeDeclaration] = stat match {
    case defn: Defn.Class  => Some(TypeDeclaration(defn.name.value, kindOfClass(defn), defn))
    case defn: Defn.Trait  => Some(TypeDeclaration(defn.name.value, "trait", defn))
    case defn: Defn.Enum   => Some(TypeDeclaration(defn.name.value, "enum", defn))
    case defn: Defn.Type   => Some(TypeDeclaration(defn.name.value, "type", defn))
    case decl: Decl.Type   => Some(TypeDeclaration(decl.name.value, "type", decl))
    case defn: Defn.Object => Some(TypeDeclaration(defn.name.value, "object", defn))
    case _                 => None
  }

  private def refinementConstraintNames(stats: List[Stat]): Set[String] =
    stats.collect { case defn: Defn.Object => defn }
      .flatMap(_.templ.inits)
      .flatMap(_.tpe.collect { case name: Type.Name => name.value })
      .toSet

  private def isConstraintAlias(declaration: TypeDeclaration, constraintNames: Set[String]): Boolean =
    declaration.tree match {
      case alias: Defn.Type => constraintNames.contains(alias.name.value) && !isNewtypeAlias(alias)
      case _                => false
    }

  private def isNewtypeAlias(alias: Defn.Type): Boolean =
    alias.body match {
      case Type.Select(Term.Name(owner), Type.Name("T")) => owner == alias.name.value
      case _                                             => false
    }

  private def kindOfClass(defn: Defn.Class): String =
    if (defn.mods.exists(_.is[Mod.Case])) "case class" else "class"

  private def fileStem(input: Input): Option[String] = {
    val path = input match {
      case file: Input.File          => Some(file.path.toNIO.getFileName.toString)
      case virtual: Input.VirtualFile => Some(virtual.path)
      case _                         => None
    }
    path.map(name => name.split('/').last.stripSuffix(".scala").stripSuffix(".sc"))
  }
}
