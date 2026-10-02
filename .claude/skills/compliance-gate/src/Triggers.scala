package compliancegate

import java.nio.file.FileSystems
import java.nio.file.Paths

case class ProjectContext(
    root: os.Path,
    buildDescriptor: String,
    fileHeads: Map[os.RelPath, String]
)

object ProjectContext:
  def build(root: os.Path, files: List[ChangedFile]): ProjectContext =
    val readable = files.filter(_.status != FileStatus.Deleted)
    val heads = readable.map(file => file.path -> Diff.readFileHead(root, file.path)).toMap
    ProjectContext(root, Diff.readBuildDescriptor(root), heads)

object Triggers:
  def applicable(
      skill: SkillFrontmatter,
      files: List[ChangedFile],
      context: ProjectContext
  ): Boolean =
    skill.triggers.exists(_.exists(entry => files.exists(file => entryMatches(entry, file, context))))

  def filesMatching(
      skill: SkillFrontmatter,
      files: List[ChangedFile],
      context: ProjectContext
  ): List[ChangedFile] =
    val entries = skill.triggers.getOrElse(List.empty)
    files.filter(file => entries.exists(entry => entryMatches(entry, file, context)))

  private def entryMatches(entry: TriggerEntry, file: ChangedFile, context: ProjectContext) =
    val excluded = entry.pathsExclude.exists(Glob.matches(_, file.path))
    if excluded then false
    else
      val predicates = collectPredicates(entry, file, context)
      if predicates.isEmpty then false
      else
        entry.matchMode match
          case TriggerMatch.Any => predicates.exists(identity)
          case TriggerMatch.All => predicates.forall(identity)

  private def collectPredicates(
      entry: TriggerEntry,
      file: ChangedFile,
      context: ProjectContext
  ) =
    val filePred = Option.when(entry.filePatterns.nonEmpty):
      entry.filePatterns.exists(glob => Glob.matches(glob, file.path))
    val importPred = Option.when(entry.imports.nonEmpty):
      ImportMatch.anyMatches(entry.imports, context.fileHeads.getOrElse(file.path, ""))
    val depPred = Option.when(entry.dependencies.nonEmpty):
      entry.dependencies.exists(token => context.buildDescriptor.contains(token))
    List(filePred, importPred, depPred).flatten

object Glob:
  def matches(glob: String, path: os.RelPath): Boolean =
    val target = Paths.get(path.toString)
    variants(glob).exists(variant => matcher(variant).matches(target))

  private def variants(glob: String) =
    if glob.startsWith("**/") then List(glob, glob.stripPrefix("**/")) else List(glob)

  private def matcher(glob: String) =
    FileSystems.getDefault.getPathMatcher(s"glob:$glob")
